package com.github.krowikbobton.searchstringoccurrencesintellijplugin.search


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.NoSuchFileException
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.isReadable
import java.io.FileNotFoundException
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOn


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
    searchHidden: Boolean = true
): Flow<Occurrence> {

    if (directory.toString() == ""){
        throw IllegalArgumentException("Directory path must not be empty")
    }
    if (!directory.exists()) {
        throw NoSuchFileException("Directory $directory does not exist")
    }

    if (!directory.isReadable()) {
        throw AccessDeniedException("Main directory $directory is not readable")
    }

    if (!directory.isDirectory()) {
        throw IllegalArgumentException("Provided path: $directory does not lead to a directory")
    }

    if (stringToSearch.contains("\n")) {
        throw IllegalArgumentException("A pattern string cannot contain newlines!")
    }

    if (stringToSearch.isEmpty()) {
        throw IllegalArgumentException("A pattern string cannot be empty!")
    }

    if (!searchHidden && directory.isHidden()) {
        throw IllegalArgumentException("The starting directory $directory is hidden, but 'Search hidden files' is disabled")
    }

    if(VIRTUAL_FILESYSTEMS.any {directory.toAbsolutePath().normalize().startsWith(it) }) {
        throw IllegalArgumentException("The starting directory $directory is in a virtual folder")
    }

    return channelFlow {
        // Limit number of files being read at the same time to half of
        // the available cores to prevent overwhelming processing units
        val coreCount =  Runtime.getRuntime().availableProcessors()
        val filesAtOnce = (coreCount / 2).coerceAtLeast(1)
        val semaphore = Semaphore(filesAtOnce)

        try {
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
                    if ((!searchHidden && file.isHidden()) || !file.isReadable()
                        || !attrs.isRegularFile
                    ) {
                        return FileVisitResult.CONTINUE
                    }
                    // Launching coroutine with Dispatchers.IO, because accessing and reading
                    // files require many I/O operations.
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
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
                            } catch (e: IOException) { // exceptions other than I/O will be thrown further
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
                    return FileVisitResult.CONTINUE
                }
            }
            Files.walkFileTree(directory, visitor)
        } catch(e : CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Critical error during file walk: ${e.message}", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)
}