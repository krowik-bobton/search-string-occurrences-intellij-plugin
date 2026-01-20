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
import kotlin.io.path.isDirectory
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

private val logger = Logger.getInstance(object{} :: class.java.enclosingClass)

// Entering virtual filesystems will most likely result in loop or exceeding memory limit
private val VIRTUAL_FILESYSTEMS = setOf(
    "/proc",
    "/sys",
    "/dev",
    "/run"
)


interface Occurrence {
    val file: Path
    val line: Int
    val offset: Int
}

data class OccurrenceInfo(
    override val file: Path,
    override val line: Int,
    override val offset: Int
) : Occurrence

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
 * Search for occurrences of `stringToSearch` inside files of `directory`
 * @param stringToSearch non-empty pattern (can't contain newlines)
 * @param directory starting directory
 * @param searchHidden whether to search hidden files/directories
 *
 * @return Flow of occurrences (path, line, offset)
 */
fun searchForTextOccurrences(
    stringToSearch: String,
    directory: Path,
    searchHidden: Boolean = false
): Flow<Occurrence> {

    require(directory.exists()) {"Directory $directory does not exist"}
    require (directory.isReadable()) {"Main directory $directory is not readable"}
    require(directory.isDirectory()) { "Provided path: $directory does not lead to a directory"}
    require(!stringToSearch.contains("\n")) {"A pattern string cannot contain newlines!"}
    require(!stringToSearch.isEmpty()) { "A pattern string cannot be empty!" }
    require(searchHidden || !directory.isHidden()) {
        "The starting directory $directory is hidden, but 'Search hidden files' is disabled"
    }
    require(!VIRTUAL_FILESYSTEMS.any {directory.toAbsolutePath().normalize().startsWith(it) }) {
        "The starting directory $directory is in a virtual folder"
    }

    return channelFlow {
        // Limit number of files being read at the same time to half of
        // the available cores to prevent overwhelming processing units
        val coreCount =  Runtime.getRuntime().availableProcessors()
        val filesAtOnce = (coreCount / 2).coerceAtLeast(1)

        val filesChannel = Channel<Path>(3_000)

        val consumers = List(filesAtOnce){id ->
            launch(Dispatchers.IO){
                for(file in filesChannel){
                    val isBinary = isFileBinary(file)
                    if(isBinary) continue;
                    var lineNumber = 1
                    try {
                        file.toFile().useLines { lines ->
                            for (line in lines) {
                                ensureActive()
                                var lastIndex = 0   // index of last pattern occurrence in this line
                                while (lastIndex != -1 && lastIndex < line.length) {
                                    lastIndex = line.indexOf(stringToSearch, lastIndex)
                                    if (lastIndex != -1) {
                                        val occurrence = OccurrenceInfo(file, lineNumber, lastIndex)
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
                            // started reading it. Such situations are not critical.
                            logger.warn("Skipped missing file: $file")
                        } else {
                            logger.error("Problem reading the file: $file : $e")
                        }
                    }
                }
            }
        }

        val producer = launch(Dispatchers.IO){
            try {
                runInterruptible {  // Cały file walk w interruptible
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
                            // It's not a critical error if file couldn't be visited, continue searching
                            logger.warn("Could not visit $file", exc)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            ensureActive()
                            if ((!searchHidden && file.isHidden()) || !file.isReadable() || !attrs.isRegularFile) {
                                return FileVisitResult.CONTINUE
                            }
                            runBlocking {
                                filesChannel.send(file)
                            }
                            return FileVisitResult.CONTINUE
                        }
                    }
                    Files.walkFileTree(directory, visitor)
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
        consumers.joinAll()
        producer.join()
    }.flowOn(Dispatchers.IO)
}