package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A slim dropdown component that matches the SlimTextField styling
 * Supports nullable values where null means "no selection"
 */
@Composable
fun <T> SlimDropdown(
    value: T?,
    options: List<T>,
    onValueChange: (T?) -> Unit,
    displayText: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    allowUnselect: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(2.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (value != null) displayText(value) else placeholder,
                color = if (value != null) Color(0xFFE0E0E0) else Color(0xFF6A6A6A),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Dropdown",
                tint = Color(0xFFB0B0B0),
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2B2B2B)),
        ) {
            // Add "None" option if allowUnselect is true and there are options
            if (allowUnselect && options.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "None",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(null)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }

            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayText(option),
                            color = Color(0xFFE0E0E0),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}

/**
 * A slim dropdown component that supports custom text color per item
 * Supports nullable values where null means "no selection"
 */
@Composable
fun <T> SlimDropdownWithColor(
    value: T?,
    options: List<T>,
    onValueChange: (T?) -> Unit,
    displayText: (T) -> String,
    textColor: (T) -> Color,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    allowUnselect: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(2.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (value != null) displayText(value) else placeholder,
                color = if (value != null) textColor(value) else Color(0xFF6A6A6A),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Dropdown",
                tint = Color(0xFFB0B0B0),
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2B2B2B)),
        ) {
            // Add "None" option if allowUnselect is true and there are options
            if (allowUnselect && options.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "None",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(null)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }

            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayText(option),
                            color = textColor(option),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}
