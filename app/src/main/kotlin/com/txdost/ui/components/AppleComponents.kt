package com.txdost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.txdost.ui.theme.Apple

// ── Layout ───────────────────────────────────────────────────

@Composable
fun AppleScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Apple.colors.bg),
        content = content
    )
}

@Composable
fun AppleTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = Apple.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.bg)
            .statusBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                ) {
                    Icon(Icons.Rounded.ChevronLeft, "Back", tint = c.blue, modifier = Modifier.size(30.dp))
                }
            }
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.label, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel, maxLines = 1)
                }
            }
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        HorizontalDivider(color = c.separator, thickness = 0.5.dp)
    }
}

// ── Buttons ──────────────────────────────────────────────────

enum class AppleButtonStyle { Filled, Tinted, Gray, Plain, Destructive }

@Composable
fun AppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppleButtonStyle = AppleButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val c = Apple.colors
    val bg: Color
    val fg: Color
    when (style) {
        AppleButtonStyle.Filled -> { bg = c.blue; fg = Color.White }
        AppleButtonStyle.Tinted -> { bg = c.blue.copy(alpha = 0.15f); fg = c.blue }
        AppleButtonStyle.Gray -> { bg = c.fill; fg = c.label }
        AppleButtonStyle.Plain -> { bg = Color.Transparent; fg = c.blue }
        AppleButtonStyle.Destructive -> { bg = c.red.copy(alpha = 0.15f); fg = c.red }
    }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = c.fill.copy(alpha = 0.4f),
            disabledContentColor = c.tertiaryLabel
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = fg
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

// ── Cards / grouped lists ────────────────────────────────────

@Composable
fun AppleCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Apple.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.secondaryBg)
            .padding(padding),
        content = content
    )
}

@Composable
fun AppleSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Apple.colors.secondaryLabel,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
fun AppleGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Apple.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondaryBg),
        content = content
    )
}

@Composable
fun AppleRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailingText: String? = null,
    valueColor: Color? = null,
    showChevron: Boolean = false,
    showDivider: Boolean = true,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val c = Apple.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable { onClick() } else it }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background((iconTint ?: c.blue).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = (iconTint ?: c.blue), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (danger) c.red else c.label
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
                }
            }
            if (trailingText != null) {
                Text(
                    trailingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor ?: c.secondaryLabel,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(20.dp))
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = c.separator,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = if (icon != null) 58.dp else 16.dp)
            )
        }
    }
}

// ── Text field ───────────────────────────────────────────────

@Composable
fun AppleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    val c = Apple.colors
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = c.secondaryLabel,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = c.tertiaryLabel, style = MaterialTheme.typography.bodyLarge) },
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.secondaryBg,
                unfocusedContainerColor = c.secondaryBg,
                disabledContainerColor = c.secondaryBg,
                focusedBorderColor = c.blue,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = c.label,
                unfocusedTextColor = c.label,
                cursorColor = c.blue
            )
        )
    }
}

// ── Badges & switches ────────────────────────────────────────

@Composable
fun AppleBadge(text: String, active: Boolean = true, tint: Color? = null) {
    val c = Apple.colors
    val col = tint ?: if (active) c.green else c.gray
    Row(
        Modifier
            .clip(CircleShape)
            .background(col.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AppleSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    showDivider: Boolean = true
) {
    val c = Apple.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = c.label)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = c.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = c.gray
                )
            )
        }
        if (showDivider) {
            HorizontalDivider(color = c.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// ── Segmented control ────────────────────────────────────────

@Composable
fun AppleSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedColors: List<Color>? = null
) {
    val c = Apple.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(c.fill)
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val selColor = selectedColors?.getOrNull(index) ?: c.blue
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .let { if (selected) it.background(selColor) else it }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Color.White else c.label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun AppleScrollingChips(
    items: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (text, active) -> AppleBadge(text, active) }
    }
}

// ── Menus (iOS-style) ────────────────────────────────────────

@Composable
fun AppleMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Apple.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 220.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = c.secondaryBg,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
fun AppleMenuRow(
    text: String,
    icon: ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val c = Apple.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) c.red else c.blue, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = if (danger) c.red else c.label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AppleMenuDivider() {
    HorizontalDivider(
        color = Apple.colors.separator,
        thickness = 0.5.dp,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun AppleAlertDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = "Cancel",
    destructive: Boolean = false,
    content: @Composable () -> Unit
) {
    val c = Apple.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = c.secondaryBg,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = c.label) },
        text = { content() },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) c.red else c.blue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = if (dismissText != null) {
            { TextButton(onClick = onDismiss) { Text(dismissText, color = c.blue) } }
        } else null
    )
}

