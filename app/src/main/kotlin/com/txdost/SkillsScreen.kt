package com.txdost.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.txdost.ui.components.*
import com.txdost.ui.theme.Apple
import com.txdost.viewmodel.ChatViewModel

@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel()
) {
    val c = Apple.colors
    val uiState by chatViewModel.uiState.collectAsState()

    var showEditor by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var instr by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }

    AppleScreen {
        AppleTopBar(title = "Skills", subtitle = "Shape how the AI replies", onBack = onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // ── Mode ──────────────────────────────────────────
            AppleSectionHeader("Mode")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleSegmentedControl(
                    options = listOf("Build", "Plan"),
                    selectedIndex = if (uiState.agentMode == "plan") 1 else 0,
                    onSelect = { chatViewModel.setAgentMode(if (it == 1) "plan" else "build") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (uiState.agentMode == "plan")
                        "Plan mode — AI sirf short plan degi, kuch execute nahi karegi."
                    else
                        "Build mode — AI commands chala kar kaam turant karegi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondaryLabel
                )
            }

            // ── Skills ────────────────────────────────────────
            AppleSectionHeader("Skills")
            uiState.skills.forEach { skill ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                    AppleCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        skill.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = c.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (skill.builtIn) AppleBadge("Built-in") else AppleBadge("Custom")
                                }
                                if (skill.description.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        skill.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.secondaryLabel
                                    )
                                }
                            }
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { chatViewModel.toggleSkill(skill.id) },
                                colors = SwitchDefaults.colors(checkedTrackColor = c.green)
                            )
                        }
                        if (!skill.builtIn) {
                            Spacer(Modifier.height(6.dp))
                            Row {
                                AppleButton(
                                    "Edit",
                                    {
                                        editId = skill.id
                                        name = skill.name
                                        desc = skill.description
                                        instr = skill.instruction
                                        showEditor = true
                                    },
                                    style = AppleButtonStyle.Tinted
                                )
                                Spacer(Modifier.width(8.dp))
                                AppleButton("Delete", { deleteId = skill.id }, style = AppleButtonStyle.Destructive)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppleButton(
                    "Add custom skill",
                    {
                        editId = null; name = ""; desc = ""; instr = ""; showEditor = true
                    },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Add
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showEditor) {
        Dialog(onDismissRequest = { showEditor = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = c.bg, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text(
                        if (editId == null) "Add custom skill" else "Edit skill",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.label
                    )
                    Spacer(Modifier.height(16.dp))
                    AppleTextField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Roman Urdu replies")
                    Spacer(Modifier.height(10.dp))
                    AppleTextField(value = desc, onValueChange = { desc = it }, label = "Description", placeholder = "Short note (optional)")
                    Spacer(Modifier.height(10.dp))
                    AppleTextField(
                        value = instr,
                        onValueChange = { instr = it },
                        label = "Instruction",
                        placeholder = "Tell the AI how to behave…",
                        singleLine = false,
                        maxLines = 6
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AppleButton(
                            "Cancel",
                            { showEditor = false },
                            Modifier.weight(1f),
                            style = AppleButtonStyle.Gray
                        )
                        Spacer(Modifier.width(10.dp))
                        AppleButton(
                            "Save",
                            {
                                if (editId == null) chatViewModel.addSkill(name, desc, instr)
                                else chatViewModel.updateSkill(editId!!, name, desc, instr)
                                showEditor = false
                            },
                            Modifier.weight(1f),
                            enabled = name.isNotBlank() && instr.isNotBlank()
                        )
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        AppleAlertDialog(
            title = "Delete skill?",
            onDismiss = { deleteId = null },
            confirmText = "Delete",
            onConfirm = { chatViewModel.deleteSkill(id); deleteId = null },
            destructive = true
        ) {
            Text("Ye custom skill hata di jaayegi.", color = c.secondaryLabel)
        }
    }
}
