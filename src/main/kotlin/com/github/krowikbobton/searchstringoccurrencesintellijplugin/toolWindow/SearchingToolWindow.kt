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

    // GENERAL //
    private val mainScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
    private var searchJob: Job? = null
    // searchJob starts after clicking Start button and can be cancelled by clicking Cancel


    // UI components //
    private val dirField = JTextField()
    private val stringField = JTextField("pattern")
    private val startButton = JButton("Start search")
    private val cancelButton = JButton("Cancel search")
    private val resultsArea = JTextArea()
    private val searchHiddenCheckBox = JCheckBox("Search hidden files")



    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        //We have to cancel all coroutines jobs when IDE window is being closed
        Disposer.register(toolWindow.disposable) {
            mainScope.cancel()
        }


        // CONFIGURE UI COMPONENTS //
        cancelButton.isEnabled = false
        resultsArea.isEditable = false
        dirField.text = project.basePath.toString()
        // initial value of the dirField: directory of current project.


        // MAIN PANEL //
        val mainPanel = JPanel(BorderLayout(10, 10))


        // SCROLL PANE //
        val scrollPane = JBScrollPane(resultsArea)


        // TOP PANEL //
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(JLabel("Directory Path:"))
        topPanel.add(dirField)
        topPanel.add(JLabel("String to Search:"))
        topPanel.add(stringField)


        // BUTTON PANEL //
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(startButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(searchHiddenCheckBox)
        topPanel.add(buttonPanel)


        // COMBINE PANELS //
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)


        //  ADDING THE WINDOW //
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)


        // START_BUTTON ON-CLICK ACTION //
        startButton.addActionListener {
            search()
        }


        // CANCEL_BUTTON ON-CLICK ACTION //
        cancelButton.addActionListener {
            searchJob?.cancel()
            searchHiddenCheckBox.isEnabled = true
        }
    }

    /**
     * Private function for searching.
     * Runs after clicking Start button
     */
    private fun search(){
        resultsArea.text = ""
        val firstDirectory = dirField.text
        val pattern = stringField.text
        val searchHiddenFiles = searchHiddenCheckBox.isSelected
        startButton.isEnabled = false
        cancelButton.isEnabled = true
        searchHiddenCheckBox.isEnabled = false
        dirField.isEnabled = false
        stringField.isEnabled = false
        resultsArea.text = "Started searching for $pattern in $firstDirectory\n\n"

        // Buffer for storing results. Since appending text to JTextArea is expensive,
        // we will append maxBufferSize lines as a single String
        val resultsBuffer = mutableListOf<String>()
        val maxBufferSize = 256

        // searchJob can be cancelled by clicking Cancel button
        searchJob = mainScope.launch {
            try {
                val directory = Paths.get(firstDirectory)
                val occurrenceFlow = searchForTextOccurrences(
                    pattern,
                    directory,
                    searchHiddenFiles)

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
                if(resultsBuffer.isNotEmpty()){
                    // If user cancelled searching and the buffer of the results is not empty:
                    resultsArea.append(resultsBuffer.joinToString (""))
                    resultsBuffer.clear()
                }
                resultsArea.append("\n--- Searching cancelled by User ---\n")
                throw e
            } catch(e : IllegalArgumentException){
                resultsArea.append("--- ERROR --- \n${e.message}\n")
            }
            catch(e : AccessDeniedException){
                resultsArea.append("--- ERROR --- \n${e.message}\n")
            }
            catch (e: Exception) {
                resultsArea.append("--- ERROR ---\nUnexpected error occurred \n${e.message}\n")
            } finally {
                startButton.isEnabled = true
                cancelButton.isEnabled = false
                searchHiddenCheckBox.isEnabled = true
                dirField.isEnabled = true
                stringField.isEnabled = true
            }
        }
    }
}


