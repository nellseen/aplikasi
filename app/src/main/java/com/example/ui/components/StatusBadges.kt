package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.process.ProcessStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.GreenRunning
import com.example.ui.theme.NodeGreen
import com.example.ui.theme.PythonBlue
import com.example.ui.theme.RedError
import com.example.ui.theme.Slate700

@Composable
fun ProcessStatusBadge(status: ProcessStatus, exitCode: Int? = null, modifier: Modifier = Modifier) {
    val (bgColor, textColor, label) = when (status) {
        ProcessStatus.RUNNING -> Triple(GreenRunning.copy(alpha = 0.2f), GreenRunning, "RUNNING")
        ProcessStatus.STARTING -> Triple(CyanPrimary.copy(alpha = 0.2f), CyanPrimary, "STARTING")
        ProcessStatus.STOPPING -> Triple(AmberWarning.copy(alpha = 0.2f), AmberWarning, "STOPPING")
        ProcessStatus.STOPPED -> {
            val txt = if (exitCode != null && exitCode != 0) "STOPPED ($exitCode)" else "STOPPED"
            Triple(Color.Gray.copy(alpha = 0.2f), Color.LightGray, txt)
        }
        ProcessStatus.FAILED -> Triple(RedError.copy(alpha = 0.2f), RedError, "FAILED (${exitCode ?: 1})")
        ProcessStatus.IDLE -> Triple(Slate700.copy(alpha = 0.4f), Color.LightGray, "IDLE")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == ProcessStatus.RUNNING) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alphaAnim)
                    .clip(CircleShape)
                    .background(GreenRunning)
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (status == ProcessStatus.STARTING || status == ProcessStatus.STOPPING) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RuntimeBadge(runtimeType: String, modifier: Modifier = Modifier) {
    val (bgColor, tintColor, label) = when (runtimeType.uppercase()) {
        "NODE" -> Triple(NodeGreen.copy(alpha = 0.15f), NodeGreen, "Node.js")
        "PYTHON" -> Triple(PythonBlue.copy(alpha = 0.15f), PythonBlue, "Python 3")
        else -> Triple(Color.Gray.copy(alpha = 0.15f), Color.LightGray, runtimeType)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = tintColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun DependencyStatusBadge(status: String, count: Int, modifier: Modifier = Modifier) {
    val (bgColor, tint, icon, text) = when (status) {
        "INSTALLED" -> Quad(GreenRunning.copy(alpha = 0.15f), GreenRunning, Icons.Default.CheckCircle, "Installed ($count)")
        "INSTALLING" -> Quad(CyanPrimary.copy(alpha = 0.15f), CyanPrimary, Icons.Default.HourglassTop, "Installing...")
        "DEPENDENCIES_FOUND" -> Quad(AmberWarning.copy(alpha = 0.15f), AmberWarning, Icons.Default.Info, "$count Required")
        "FAILED" -> Quad(RedError.copy(alpha = 0.15f), RedError, Icons.Default.Error, "Install Failed")
        "NONE_REQUIRED" -> Quad(Color.Gray.copy(alpha = 0.15f), Color.Gray, Icons.Default.CheckCircle, "None")
        else -> Quad(Color.Gray.copy(alpha = 0.15f), Color.Gray, Icons.Default.Info, status)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
