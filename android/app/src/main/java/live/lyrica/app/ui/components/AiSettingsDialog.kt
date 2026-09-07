package live.lyrica.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.ai.AiLanguages
import live.lyrica.app.ai.AiLyricsService
import live.lyrica.app.ai.AiProvider
import live.lyrica.app.ui.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsDialog(
    aiService: AiLyricsService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedProvider by remember { mutableStateOf(aiService.getSelectedProvider()) }
    var apiKey by remember { mutableStateOf(aiService.getApiKey(selectedProvider) ?: "") }
    var selectedModel by remember { mutableStateOf(aiService.getSelectedModel(selectedProvider)) }
    var customModel by remember { mutableStateOf("") }
    var isCustomModel by remember { mutableStateOf(!selectedProvider.popularModels.contains(selectedModel)) }
    var preferredLang by remember { mutableStateOf(aiService.getPreferredLanguage()) }
    var keyVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AI Translation & Romanization", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LabelPrimary)
                    Text("Bring your own API key for real-time lyrics translation", fontSize = 12.sp, color = LabelSecondary)
                }
            }

            HorizontalDivider(color = Separator)

            // Provider selection
            Text("AI PROVIDER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppleRed, letterSpacing = 1.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AiProvider.values().forEach { provider ->
                    val isSelected = selectedProvider == provider
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                selectedProvider = provider
                                apiKey = aiService.getApiKey(provider) ?: ""
                                val model = aiService.getSelectedModel(provider)
                                selectedModel = model
                                isCustomModel = !provider.popularModels.contains(model)
                                if (isCustomModel) customModel = model
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) AppleRed.copy(alpha = 0.1f) else SystemGray6,
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isSelected) AppleRed else Color.Transparent
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(
                                provider.displayName,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AppleRed else LabelPrimary
                            )
                        }
                    }
                }
            }

            // API Key Input
            Text("${selectedProvider.displayName} API Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabelPrimary)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("Enter your ${selectedProvider.displayName} API Key") },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                            tint = SystemGray
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppleRed,
                    unfocusedBorderColor = SystemGray4
                )
            )

            // Model Selection
            Text("MODEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppleRed, letterSpacing = 1.sp)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedProvider.popularModels.forEach { model ->
                    val isSelected = !isCustomModel && selectedModel == model
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            isCustomModel = false
                            selectedModel = model
                        },
                        label = { Text(model.substringAfterLast('/'), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppleRed.copy(alpha = 0.15f),
                            selectedLabelColor = AppleRed
                        )
                    )
                }
                FilterChip(
                    selected = isCustomModel,
                    onClick = {
                        isCustomModel = true
                        if (customModel.isNotBlank()) selectedModel = customModel
                    },
                    label = { Text("Custom Model", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppleRed.copy(alpha = 0.15f),
                        selectedLabelColor = AppleRed
                    )
                )
            }

            if (isCustomModel) {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        selectedModel = it
                    },
                    placeholder = { Text("e.g. llama-3.3-70b-versatile or deepseek/deepseek-r1") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppleRed,
                        unfocusedBorderColor = SystemGray4
                    )
                )
            }

            // Preferred Language
            Text("PREFERRED TARGET LANGUAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppleRed, letterSpacing = 1.sp)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiLanguages.POPULAR_LANGUAGES.forEach { lang ->
                    val isSelected = preferredLang.equals(lang, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { preferredLang = lang },
                        label = { Text(lang, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppleRed.copy(alpha = 0.15f),
                            selectedLabelColor = AppleRed
                        )
                    )
                }
            }

            OutlinedTextField(
                value = preferredLang,
                onValueChange = { preferredLang = it },
                label = { Text("Custom Target Language") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppleRed,
                    unfocusedBorderColor = SystemGray4
                )
            )

            Spacer(Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = {
                    aiService.setSelectedProvider(selectedProvider)
                    aiService.setApiKey(selectedProvider, apiKey)
                    aiService.setSelectedModel(selectedProvider, selectedModel)
                    aiService.setPreferredLanguage(preferredLang)
                    onSaved()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save AI Settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}
