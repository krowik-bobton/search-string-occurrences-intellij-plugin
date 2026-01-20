package com.github.krowikbobton.searchstringoccurrencesintellijplugin.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.NoSuchFileException
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isHidden
import kotlin.io.path.isReadable
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.runInterruptible
import java.nio.file.AccessDeniedException


private const val CHANNEL_CAPACITY = 3_000      // capacity of a channel with files
private val logger = Logger.getInstance(object{} :: class.java.enclosingClass)
private val VIRTUAL_FILESYSTEMS = setOf(
    "/proc",
    "/sys",
    "/dev",
    "/run"
)

// Holds info about pattern occurrence in a file.
data class Occurrence(
    val file: Path,
    val line: Int,
    val offset: Int
)


/**
 * Determines whether provided file is a binary file. Applies such heuristic:
 * Check first 512 bytes of a file. If NUL character appeared, then file is most likely binary.
 */
fun isFileBinary(path : Path) : Boolean = runCatching {
    path.toFile().inputStream().use {stream->
        val buffer = ByteArray(512)
        val bytesRead = stream.read(buffer)
        if(bytesRead == -1) return@runCatching true // without @runCatching we would return from .use
        for(i in 0 until bytesRead){
            if(buffer[i] == 0.toByte()) return@runCatching true
        }
    }
    false
}.getOrDefault(true)



/**
 * Search for occurrences of `stringToSearch` inside files of `startPath`
 * @param stringToSearch non-empty pattern (can't contain newlines)
 * @param startPath starting path
 * @param searchHidden whether to search hidden files/directories
 *
 * @return Flow of occurrences (path, line, offset)
 */
fun searchForTextOccurrences(
    stringToSearch: String,
    startPath: Path,
    searchHidden: Boolean = false
): Flow<Occurrence> {

    // Check basic requirements
    if (!startPath.exists()) {
        throw NoSuchFileException(startPath.toString(), null, "$startPath does not exist")
    }
    if (!startPath.isReadable()) {
        throw AccessDeniedException(startPath.toString(), null, "$startPath is not readable")
    }
    require(!stringToSearch.contains("\n")) {"A pattern string cannot contain newlines!"}
    require(!stringToSearch.isEmpty()) { "A pattern string cannot be empty!" }
    require(searchHidden || !startPath.isHidden()) {
        "The starting $startPath is hidden, but 'Search hidden files' is disabled"
    }
    require(!VIRTUAL_FILESYSTEMS.any {startPath.toAbsolutePath().normalize().startsWith(it) }) {
        "The starting path $startPath is in a virtual folder"
    }

    return channelFlow {
        // Limit number of files being read at the same time to half of
        // the available cores to prevent overwhelming processing units
        val coreCount =  Runtime.getRuntime().availableProcessors()
        val filesAtOnce = (coreCount / 2).coerceAtLeast(1)

        // Channel of files found by the producer, consumers will process these files concurrently.
        val filesChannel = Channel<Path>(CHANNEL_CAPACITY)

        // Launch consumers coroutines.
        // Consumer processes files taken from a Channel until the channel is closed.
        val consumers = List(filesAtOnce){id ->
            launch(Dispatchers.IO){
                // Suspending loop: if a channel is empty and not closed, consumer suspends until new path
                // is provided by the producer.
                for(file in filesChannel){

                    // Skip binary files
                    val isBinary = isFileBinary(file)
                    if(isBinary) continue

                    var lineNumber = 1
                    try {
                        file.toFile().useLines { lines ->
                            for (line in lines) {
                                ensureActive()      // ensure that coroutine wasn't cancelled
                                var lastIndex = 0   // index of last pattern occurrence in this line
                                while (lastIndex != -1 && lastIndex < line.length) {
                                    lastIndex = line.indexOf(stringToSearch, lastIndex)
                                    if (lastIndex != -1) {
                                        val occurrence = Occurrence(file, lineNumber, lastIndex)
                                        send(occurrence)
                                        lastIndex++
                                    }
                                }
                                lineNumber++
                            }
                        }
                    } catch (e: IOException){ // exceptions other than I/O will be thrown further
                        if (e is FileNotFoundException || e is NoSuchFileException) {
                            // Mostly when file was deleted/changed by other process after we
                            // started reading it. Such situations are not critical, but they
                            // should be logged.
                            logger.warn("Skipped missing file: $file")
                        } else {
                            logger.error("Problem reading the file: $file : $e")
                        }
                    }
                }
            }
        }

        // Launch producer coroutine.
        // Uses Java SimpleFileVisitor to iterate through filesystem.
        // Sends found files to the Channel so the file can be processed by one of the consumers.
        val producer = launch(Dispatchers.IO){
            try {
                runInterruptible {
                    val visitor: SimpleFileVisitor<Path> = object : SimpleFileVisitor<Path>() {
                        // Before visiting the directory contents (after reading current directory)
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            ensureActive()
                            val absPath = dir.toAbsolutePath()
                            // We are not entering the virtual directories
                            if (VIRTUAL_FILESYSTEMS.any { absPath.startsWith(it) }) {
                                logger.warn("Skipping known virtual filesystem: $dir")
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            if (!searchHidden && dir.isHidden()) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        // After unsuccessful try of opening the directory / visiting the file or other reasons
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            ensureActive()
                            // It's not a critical error if a file couldn't be visited, continue searching
                            logger.warn("Could not visit $file", exc)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            ensureActive()
                            if ((!searchHidden && file.isHidden()) || !file.isReadable() || !attrs.isRegularFile) {
                                return FileVisitResult.CONTINUE
                            }
                            runBlocking {
                                filesChannel.send(file) // suspend if a channel is full.
                            }
                            return FileVisitResult.CONTINUE
                        }
                    }
                    Files.walkFileTree(startPath, visitor)
                }
            } catch(e : CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Critical error during file walk: ${e.message}", e)
                throw e
            }
            finally{
                filesChannel.close()
            }
        }
        producer.join()
        consumers.joinAll()
    }.flowOn(Dispatchers.IO)
}
