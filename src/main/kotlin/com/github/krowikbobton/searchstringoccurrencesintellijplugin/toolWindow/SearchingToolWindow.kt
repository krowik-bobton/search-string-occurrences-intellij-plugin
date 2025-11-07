package com.github.krowikbobton.searchstringoccurrencesintellijplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import javax.swing.*
import com.github.krowikbobton.searchstringoccurrencesintellijplugin.search.*

class SearchingToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val mainScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

        //We have to cancel all coroutines jobs when IntelliJ window is being closed
        Disposer.register(toolWindow.disposable) {
            mainScope.cancel()
        }

        var searchJob: Job? = null
        val mainPanel = JPanel(BorderLayout(10, 10))
        val dirField = JTextField(System.getProperty("user.home")) //User's home directory
        val stringField = JTextField("pattern")
        val startButton = JButton("Start search")
        val cancelButton = JButton("Cancel search")
        cancelButton.isEnabled = false

        val resultsArea = JTextArea()
        resultsArea.isEditable = false
        val scrollPane = JBScrollPane(resultsArea)

        val searchHiddenCheckBox = JCheckBox("Search hidden files")

        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(JLabel("Directory Path:"))
        topPanel.add(dirField)
        topPanel.add(JLabel("String to Search:"))
        topPanel.add(stringField)

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(startButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(searchHiddenCheckBox)

        topPanel.add(buttonPanel)
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        startButton.addActionListener {
            resultsArea.text = ""
            val firstDirectory = dirField.text
            val pattern = stringField.text
            val searchHiddenFiles = searchHiddenCheckBox.isSelected

            startButton.isEnabled = false
            cancelButton.isEnabled = true
            searchHiddenCheckBox.isEnabled = false

            // Buffer for storing results. Since appending text to JTextArea is expensive,
            // we will append maxBufferSize lines as a single String
            val resultsBuffer = mutableListOf<String>()
            val maxBufferSize = 256

            searchJob = mainScope.launch {
                try {
                    val directory = Paths.get(firstDirectory)
                    // Inside the function, conditions before channelFlow will check if provided path and string
                    // are suitable. If not, exception will be thrown.
                    val occurrenceFlow = searchForTextOccurrences(pattern, directory, searchHiddenFiles)
                    // no exception, start searching
                    resultsArea.text = "--- Searching started ---\n\n"
                    var foundAtLeastOneOccurrence = false
                    occurrenceFlow.collect { occurrence ->
                        val line =  "${occurrence.file}: ${occurrence.line}:${occurrence.offset}\n"
                        resultsBuffer.add(line)
                        if(resultsBuffer.size == maxBufferSize) {
                            resultsArea.append(resultsBuffer.joinToString(""))
                            resultsBuffer.clear()
                        }
                        foundAtLeastOneOccurrence = true
                    }
                    if(foundAtLeastOneOccurrence){
                        // append remaining lines from buffer
                        resultsArea.append(resultsBuffer.joinToString(""))
                        resultsBuffer.clear()
                        resultsArea.append("\n--- Searching completed ---")
                    }
                    else{
                        resultsArea.append("--- Nothing found ---")
                    }
                } catch (e: NoSuchFileException) {
                    resultsArea.append("--- ERROR --- \nDirectory does not exist: ${e.message}\n")
                } catch (e: CancellationException) {
                    resultsArea.append("\n--- Searching cancelled by User ---\n")
                    throw e
                } catch(e : IllegalArgumentException){
                    resultsArea.append("--- ERROR --- \n${e.message}\n")
                }
                catch (e: Exception) {
                    resultsArea.append("--- ERROR ---\nUnexpected error occurred \n${e.message}\n")
                } finally {
                    startButton.isEnabled = true
                    cancelButton.isEnabled = false
                    searchHiddenCheckBox.isEnabled = true
                }
            }
        }

        cancelButton.addActionListener {
            searchJob?.cancel()
            searchHiddenCheckBox.isEnabled = true
        }

        // Adding the window
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

