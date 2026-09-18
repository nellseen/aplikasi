package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.NodeGreen
import com.example.ui.theme.PythonBlue

@Composable
fun ImportProjectDialog(
    onImportZip: (Uri, String?) -> Unit,
    onCreateSample: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Project", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Upload a ZIP archive containing a Node.js or Python project.",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name (optional)") },
                    placeholder = { Text("Auto-detect from package metadata") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("import_project_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("select_zip_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Icon(imageVector = Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedUri != null) "ZIP Selected ✓" else "Choose ZIP Archive",
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (selectedUri != null) {
                    Text(
                        text = "File URI: ${selectedUri?.lastPathSegment}",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
                    Text("  OR CREATE SAMPLE  ", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onCreateSample("NODE")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("create_sample_node_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Node.js", color = NodeGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            onCreateSample("PYTHON")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("create_sample_python_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Python", color = PythonBlue, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedUri?.let { uri ->
                        onImportZip(uri, projectName.ifBlank { null })
                        onDismiss()
                    }
                },
                enabled = selectedUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.testTag("confirm_import_button")
            ) {
                Text("Import & Extract", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
