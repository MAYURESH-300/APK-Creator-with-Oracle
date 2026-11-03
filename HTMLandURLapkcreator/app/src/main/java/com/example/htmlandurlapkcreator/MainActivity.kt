package com.example.htmlandurlapkcreator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.example.htmlandurlapkcreator.build.BuildManager
import com.example.htmlandurlapkcreator.editor.EditorManager
import com.example.htmlandurlapkcreator.project.ProjectManager
import com.example.htmlandurlapkcreator.ui.IdeWorkspace
import com.example.htmlandurlapkcreator.ui.theme.APKCreatorTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val projectManager = ProjectManager(applicationContext)
        val editorManager = EditorManager()

        setContent {
            val scope = rememberCoroutineScope()
            val buildManager = remember { BuildManager(applicationContext, scope) }

            val initialProject = remember {
                val lastOpenedName = projectManager.getLastOpenedProjectName()
                val projectsDir = File(filesDir, "projects")

                if (lastOpenedName != null) {
                    val lastProjDir = File(projectsDir, lastOpenedName.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
                    val loaded = projectManager.loadProjectFromDirectory(lastProjDir)
                    if (loaded != null) return@remember loaded
                }

                val recent = projectManager.getRecentProjects(projectsDir)
                if (recent.isNotEmpty()) {
                    recent.first()
                } else {
                    projectManager.createProject("MyWebApp", "com.example.mywebapp")
                }
            }

            APKCreatorTheme {
                IdeWorkspace(
                    projectManager = projectManager,
                    editorManager = editorManager,
                    buildManager = buildManager,
                    initialProject = initialProject
                )
            }
        }
    }
}
