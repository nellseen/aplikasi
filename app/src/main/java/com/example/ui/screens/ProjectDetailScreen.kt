package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ProjectEntity
import com.example.process.ProcessStatus
import com.example.ui.MainViewModel
import com.example.ui.components.DependencyStatusBadge
import com.example.ui.components.FileBrowserDialog
import com.example.ui.components.LogTerminalView
import com.example.ui.components.ProcessStatusBadge
import com.example.ui.components.RuntimeBadge
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.GreenRunning
import com.example.ui.theme.RedError
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate900
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    project: ProjectEntity,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val processInfoFlow = remember(project.id) { viewModel.getProcessInfoFlow(project.id) }
    val processInfo by processInfoFlow.collectAsState()

    val logFlow = remember(project.id) { viewModel.getLogFlow(project.id) }
    val logs by logFlow.collectAsState()

    val analysis by viewModel.projectAnalysis.collectAsState()
    val files by viewModel.projectFiles.collectAsState()
    val installProgress by viewModel.installationProgress.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showFileBrowser by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var entryPointExpanded by remember { mutableStateOf(false) }

    val currentStatus = if (processInfo.status != ProcessStatus.IDLE) {
        processInfo.status
    } else {
        try {
            ProcessStatus.valueOf(project.processStatus)
        } catch (_: Exception) {
            ProcessStatus.IDLE
        }
    }

    val isRunning = currentStatus == ProcessStatus.RUNNING || currentStatus == ProcessStatus.STARTING

    LaunchedEffect(operationMessage) {
        operationMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOperationMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Text(
                            text = project.runtimeType,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Project",
                            tint = RedError
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Slate900
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Action buttons row: RUN / STOP, RESTART, INSTALL, FILES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning) {
                    Button(
                        onClick = { viewModel.stopProject(project) },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("detail_stop_button")
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { viewModel.startProject(project) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).testTag("detail_run_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RUN", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.restartProject(project) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).testTag("detail_restart_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("RESTART", color = CyanPrimary, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = { viewModel.installDependencies(project) },
                    shape = RoundedCornerShape(8.dp),
                    enabled = installProgress == null,
                    modifier = Modifier.weight(1f).testTag("detail_install_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("INSTALL", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                IconButton(
                    onClick = { showFileBrowser = true },
                    modifier = Modifier.testTag("detail_files_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Browse Files",
                        tint = Color.LightGray
                    )
                }
            }

            // Installation progress bar if active
            if (installProgress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate800, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = installProgress?.first ?: "Installing...",
                            fontSize = 11.sp,
                            color = CyanPrimary
                        )
                        Text(
                            text = "${((installProgress?.second ?: 0f) * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { installProgress?.second ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = CyanPrimary,
                        trackColor = Slate700
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata Card (Collapsible info)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Slate850),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeBadge(runtimeType = project.runtimeType)
                            ProcessStatusBadge(
                                status = currentStatus,
                                exitCode = if (processInfo.exitCode != null) processInfo.exitCode else project.lastExitCode
                            )
                        }
                        DependencyStatusBadge(status = project.dependencyStatus, count = project.dependencyCount)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Entry Point Selector
                    val candidates = analysis?.entryPointCandidates ?: listOf(project.entryPoint)
                    ExposedDropdownMenuBox(
                        expanded = entryPointExpanded,
                        onExpandedChange = { entryPointExpanded = !entryPointExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = project.entryPoint,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Entry Point") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = entryPointExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("entry_point_selector"),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = entryPointExpanded,
                            onDismissRequest = { entryPointExpanded = false }
                        ) {
                            candidates.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                                    onClick = {
                                        viewModel.updateEntryPoint(project, candidate)
                                        entryPointExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // System process details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "PID: ${if (processInfo.pid > 0) processInfo.pid else "-"}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.LightGray
                        )
                        Text(
                            text = "PM: ${project.packageManager}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.LightGray
                        )
                        if (processInfo.startTime > 0) {
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(processInfo.startTime))
                            Text(
                                text = "Started: $timeStr",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-time Terminal
            LogTerminalView(
                logs = logs,
                onClear = { viewModel.clearLogs(project.id, project.rootPath) },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    // File Browser Sheet / Dialog
    if (showFileBrowser) {
        FileBrowserDialog(
            projectName = project.name,
            projectRootPath = project.rootPath,
            files = files,
            storageManager = viewModel.storageManager,
            onDismiss = { showFileBrowser = false }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Project?") },
            text = { Text("Are you sure you want to delete '${project.name}' and all its files from storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProject(project)
                        showDeleteConfirm = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedError),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
