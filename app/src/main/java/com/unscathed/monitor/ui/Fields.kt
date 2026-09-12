package com.unscathed.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Panel {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun Hint(text: String, color: androidx.compose.ui.graphics.Color = Palette.Muted) {
    Text(text, fontSize = 12.sp, color = color)
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NumberSetting(label: String, value: Int, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    // Only overwrite what the user is typing when the value changed from outside (e.g. reset).
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun MultilineSetting(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = false,
        minLines = 5,
        maxLines = 14,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LongSetting(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.filter(Char::isDigit).toLongOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == ',' }.take(16)
            text.filter(Char::isDigit).toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun DecimalSetting(label: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toFloatOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == '.' }.take(6)
            text.toFloatOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchSetting(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (description != null) Text(description, fontSize = 12.sp, color = Palette.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
