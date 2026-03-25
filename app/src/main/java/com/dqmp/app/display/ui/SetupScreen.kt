package com.dqmp.app.display.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.R
import com.dqmp.app.display.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    currentUrl: String,
    onSave: (String, String) -> Unit
) {
    var outletId by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(currentUrl) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(140.dp)
            )

            Text(
                "Display Setup Required",
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                fontWeight = FontWeight.Black
            )

            Text(
                "CONNECT TO SYSTEM",
                style = MaterialTheme.typography.labelSmall,
                color = Emerald500,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Outlet ID Entry
            OutlinedTextField(
                value = outletId,
                onValueChange = { outletId = it },
                label = { Text("OUTLET ID", color = Slate400) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. 851b9...") },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    containerColor = Slate800,
                    focusedBorderColor = Emerald500,
                    unfocusedBorderColor = Slate700
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            )

            // API URL Entry
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("BASE API URL", color = Slate400) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    containerColor = Slate800,
                    focusedBorderColor = Emerald500,
                    unfocusedBorderColor = Slate700
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { 
                    if (outletId.isNotEmpty()) onSave(outletId, baseUrl) 
                })
            )

            // Test Audio Button
            OutlinedButton(
                onClick = { onSave("TEST_AUDIO", "TEST") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Sky400),
                border = androidx.compose.foundation.BorderStroke(1.dp, Sky400),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🔊 TEST SYSTEM AUDIO", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = { if (outletId.isNotBlank()) onSave(outletId, baseUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                enabled = outletId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Emerald500,
                    contentColor = Color.White,
                    disabledContainerColor = Slate700
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("SAVE & START DISPLAY", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            
            Text(
                "D-pad Tip: Press UP/DOWN to navigate, OK to select. Long-press BACK to reset anytime.",
                textAlign = TextAlign.Center,
                color = Slate500,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
