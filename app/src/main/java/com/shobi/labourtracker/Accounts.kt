@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.security.SecureRandom

private fun validateAccount(block: String, emailRaw: String, pw: String, all: List<Account>): String {
    val e = emailRaw.trim().lowercase()
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) return "Enter a valid email address."
    if (!(pw.length == 6 || pw.length == 8) || !pw.all(Char::isDigit)) return "Password must be 6 or 8 digits."
    if (all.any { it.block != block && it.email == e }) return "This email is already used by another block."
    return ""
}

private fun randomPassword(): String = String.format("%06d", SecureRandom().nextInt(1_000_000))

// Admin: set email + password for every block. Passwords are shown on purpose.
@Composable
fun AccountsScreen(dao: AppDao) {
    val scope = rememberCoroutineScope()
    val accounts by dao.accounts().collectAsState(emptyList())
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text("Block Accounts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Give each block member an email and a 6 or 8 digit password. They are saved in Kobo.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 6.dp), fontWeight = FontWeight.SemiBold)

        "ABCDEFG".map { it.toString() }.forEach { b ->
            AccountCard(
                block = b,
                account = accounts.firstOrNull { it.block == b },
                all = accounts,
                onSave = { email, pw ->
                    scope.launch {
                        dao.saveAccount(Account(b, email.trim().lowercase(), pw, false))
                        msg = "Block $b: " + sendAccounts(dao)
                    }
                }
            )
        }
        OutlinedButton(
            onClick = { scope.launch { msg = sendAccounts(dao) } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send unsent accounts to Kobo") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountCard(block: String, account: Account?, all: List<Account>, onSave: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var email by remember(account?.email) { mutableStateOf(account?.email ?: "") }
    var pw by remember(account?.password) { mutableStateOf(account?.password ?: "") }
    var err by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.White) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Block $block", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        account == null -> "Not set"
                        account.synced -> "Saved in Kobo"
                        else -> "Not sent yet"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (account != null && account.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            OutlinedTextField(
                email, { email = it.trim(); err = "" }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    pw, { pw = it.filter(Char::isDigit).take(8); err = "" },
                    label = { Text("Password (6 or 8 digits)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
                TextButton({ pw = randomPassword(); err = "" }) { Text("Generate") }
            }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val e = validateAccount(block, email, pw, all)
                        if (e.isEmpty()) onSave(email, pw) else err = e
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save & send") }
                if (account != null) {
                    OutlinedButton(
                        onClick = {
                            shareToWhatsApp(ctx, "Labour Tracker login\nBlock $block\nEmail: ${account.email}\nPassword: ${account.password}")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share login") }
                }
            }
        }
    }
}
