package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.MainViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ProjectDetailScreen
import com.example.ui.theme.RuntimeManagerTheme
import com.example.ui.theme.Slate900

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RuntimeManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Slate900
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val selectedProject by viewModel.selectedProject.collectAsState()

    if (selectedProject != null) {
        BackHandler {
            viewModel.selectProject(null)
        }
        ProjectDetailScreen(
            project = selectedProject!!,
            viewModel = viewModel,
            onBack = { viewModel.selectProject(null) }
        )
    } else {
        DashboardScreen(
            viewModel = viewModel,
            onProjectClick = { project ->
                viewModel.selectProject(project)
            }
        )
    }
}

