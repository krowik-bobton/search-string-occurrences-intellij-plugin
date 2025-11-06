package com.github.krowikbobton.searchstringoccurrencesintellijplugin.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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

private val logger = Logger.getInstance("searchForTextOccurrencesPlugin")


interface Occurrence {
    val file: Path
    val line: Int
    val offset: Int
}

class OccurrenceData(
    override val file: Path,
    override val line: Int,
    override val offset: Int

) : Occurrence


fun searchForTextOccurrences(
    stringToSearch: String,
    directory: Path,
    searchHidden: Boolean = true
): Flow<Occurrence> {
    if (!directory.exists()) {
        throw NoSuchFileException("Directory $directory does not exist")
    }

    if (!directory.isReadable()) {
        throw AccessDeniedException("Main directory $directory is not readable")
    }

    if (!directory.isDirectory()) {
        throw IllegalArgumentException("Provided path: $directory does not lead to a directory")
    }
    // Given string cannot contain endlines
    if (stringToSearch.contains("\n")) {
        throw IllegalArgumentException("A pattern string cannot contain endlines!")
    }

    if (stringToSearch.isEmpty()) {
        throw IllegalArgumentException("A pattern string cannot be empty!")
    }

    if (!searchHidden && directory.isHidden()) {
        throw IllegalArgumentException("The starting directory $directory is hidden, but 'Search hidden files' is disabled")
    }
    return channelFlow {

        val coreCount = Runtime.getRuntime().availableProcessors()
        val optimalConcurrency = (coreCount / 2).coerceAtLeast(1)
        //use half of available cores to avoid overwhelming the CPUs
        val semaphore = Semaphore(optimalConcurrency)
        try {
            val visitor: SimpleFileVisitor<Path> = object : SimpleFileVisitor<Path>() {
                // Before entering the directory contents (after entering the directory itself)
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureActive()
                    if (!searchHidden && dir.isHidden()) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }


                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    ensureActive()
                    logger.warn("Could not visit $file", exc)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureActive()
                    if ((!searchHidden && file.isHidden()) || !file.isReadable() || !attrs.isRegularFile) {
                        return FileVisitResult.CONTINUE
                    }
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            var lineNumber = 1
                            try {
                                file.toFile().useLines { lines ->
                                    for (line in lines) {
                                        ensureActive()
                                        // now line is a String representing one line of file
                                        var lastIndex = 0
                                        while (lastIndex != -1 && lastIndex < line.length) {
                                            lastIndex = line.indexOf(stringToSearch, lastIndex)
                                            if (lastIndex != -1) { // if we found something

                                                val occurrence = OccurrenceData(file, lineNumber, lastIndex)
                                                send(occurrence)
                                                lastIndex++
                                            }
                                        }
                                        lineNumber++
                                    }
                                }
                            } catch (e: IOException) {
                                if (e is FileNotFoundException || e is NoSuchFileException) {
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
        } catch (e: Exception) {
            close(e)
        }
    }.flowOn(Dispatchers.IO)
}
