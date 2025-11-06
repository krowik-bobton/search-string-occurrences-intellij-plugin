package com.github.krowikbobton.searchstringoccurrencesintellijplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import javax.swing.*
import com.github.krowikbobton.searchstringoccurrencesintellijplugin.search.*

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        // --- 1. Stworzenie Scope'u dla Coroutines ---
        // Tworzymy CoroutineScope, który działa na głównym wątku UI (Dispatchers.Swing)
        // SupervisorJob() sprawia, że anulowanie jednego dziecka (np. przez błąd) nie anuluje całego scope'u.
        val uiScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

        // WAŻNE: Musimy anulować ten scope, gdy okno IntelliJ jest zamykane.
        // Inaczej spowodujemy wyciek pamięci.
        // `toolWindow.disposable` to "cykl życia" tego okna.
        Disposer.register(toolWindow.disposable) {
            uiScope.cancel("Tool window is being closed")
        }

        // Zmienna do śledzenia naszego aktywnego zadania wyszukiwania
        var searchJob: Job? = null

        // --- 2. Budowa UI (Twój kod Swinga) ---
        val mainPanel = JPanel(BorderLayout(10, 10))


        val dirField = JTextField(System.getProperty("user.home")) // Lepiej zacząć od katalogu domowego
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
        mainPanel.add(scrollPane)

        // --- 3. Logika Przycisków (Nowy kod) ---

        startButton.addActionListener {
            resultsArea.text = ""

            // Pobierz tekst z pól
            val directory = dirField.text
            val pattern = stringField.text
            val searchHiddenFiles = searchHiddenCheckBox.isSelected

            // Zmień stan przycisków (UX)
            startButton.isEnabled = false
            cancelButton.isEnabled = true
            searchHiddenCheckBox.isEnabled = false

            val buffer = mutableListOf<String>()
            val maxBufferSize = 1024

            // Uruchom korutynę na naszym UI Scope!
            searchJob = uiScope.launch {
                try {
                    // Konwertuj string na Path
                    val directory = Paths.get(directory)

                    // Stwórz Flow (to jest "zimne", jeszcze nie uruchamia wyszukiwania)
                    val occurrenceFlow = searchForTextOccurrences(pattern, directory, searchHiddenFiles)
                    resultsArea.text = "--- Searching started ---\n\n"
                    // Zacznij zbierać Flow (teraz rusza wyszukiwanie)
                    // Ponieważ jesteśmy w `uiScope` (Dispatchers.Swing),
                    // ten blok `collect` jest bezpieczny dla UI.
                    var foundAtLeastOneOccurrence = false
                    occurrenceFlow.collect { occurrence ->
                        val line =  "${occurrence.file}: ${occurrence.line}:${occurrence.offset}\n"
                        buffer.add(line)
                        if(buffer.size == maxBufferSize) {
                            resultsArea.append(buffer.joinToString(""))
                            buffer.clear()
                        }
                        foundAtLeastOneOccurrence = true
                    }
                    if(foundAtLeastOneOccurrence){
                        resultsArea.append(buffer.joinToString(""))
                        buffer.clear()
                        resultsArea.append("\n--- Searching completed ---")
                    }
                    else{
                        resultsArea.append("--- Nothing found ---")
                    }
                } catch (e: NoSuchFileException) {
                    resultsArea.append("--- ERROR --- \nDirectory does not exist: ${e.message}\n")
                } catch (e: CancellationException) {
                    // To jest normalne, gdy użytkownik kliknie "Cancel"
                    resultsArea.append("\n--- Searching cancelled by User ---\n")
                    throw e
                } catch(e : IllegalArgumentException){
                    resultsArea.append("--- ERROR --- \n${e.message}\n")
                }
                catch (e: Exception) {
                    // Złap wszystkie inne błędy (np. brak uprawnień)
                    resultsArea.append("--- ERROR ---\nUnexpected error occurred \n${e.message}\n")
                } finally {
                    // Niezależnie, czy się udało, czy nie, przywróć stan przycisków
                    startButton.isEnabled = true
                    cancelButton.isEnabled = false
                    searchHiddenCheckBox.isEnabled = true
                }
            }
        }

        cancelButton.addActionListener {
            // Anuluj aktywne zadanie
            searchJob?.cancel()
            searchHiddenCheckBox.isEnabled = true
        }

        // --- 4. Dodanie Panelu do IntelliJ ---
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

