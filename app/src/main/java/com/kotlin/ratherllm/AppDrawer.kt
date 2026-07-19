package com.kotlin.ratherllm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The navigation drawer. Two panes share the sheet: the main list (new chat, saved
 * replies entry, conversation history, footer nav) and a "Saved replies" sub-pane.
 */
@Composable
fun AppDrawer(
    svc: InferenceService,
    deviceLine: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRequestExportConversation: (String) -> Unit,
    onRequestDeleteConversation: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenExperimental: () -> Unit,
    onOpenSavedReply: (SavedReply) -> Unit,
    onToast: (String) -> Unit,
) {
    val conversations by svc.conversations.collectAsState()
    val currentId by svc.currentConversationId.collectAsState()
    val savedReplies by svc.savedReplies.collectAsState()
    val models by svc.models.collectAsState()
    val experimentalOn by svc.experimentalEnabled.collectAsState()
    var savedPane by remember { mutableStateOf(false) }

    Column(
        Modifier
            .width(288.dp)
            .fillMaxHeight()
            .background(AppColors.SurfaceDrawer)
            .border(0.dp, Color.Transparent)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        if (savedPane) {
            SavedRepliesPane(
                saved = savedReplies,
                onBack = { savedPane = false },
                onOpen = onOpenSavedReply,
                onUnsave = { svc.removeSaved(it); onToast("Removed from saved") },
                onToast = onToast,
            )
        } else {
            DrawerMain(
                deviceLine = deviceLine,
                conversations = conversations,
                currentId = currentId,
                savedCount = savedReplies.size,
                modelCount = models.size,
                experimentalOn = experimentalOn,
                onNewChat = onNewChat,
                onOpenSaved = { savedPane = true },
                onSelectConversation = onSelectConversation,
                onRequestExportConversation = onRequestExportConversation,
                onRequestDeleteConversation = onRequestDeleteConversation,
                onOpenSettings = onOpenSettings,
                onOpenModels = onOpenModels,
                onOpenExperimental = onOpenExperimental,
            )
        }
    }
}

@Composable
private fun ColumnScope.DrawerMain(
    deviceLine: String,
    conversations: List<Conversation>,
    currentId: String,
    savedCount: Int,
    modelCount: Int,
    experimentalOn: Boolean,
    onNewChat: () -> Unit,
    onOpenSaved: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRequestExportConversation: (String) -> Unit,
    onRequestDeleteConversation: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenExperimental: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 0.dp).padding(bottom = 14.dp)) {
        Text("ratherllm", style = AppText.WordmarkLarge, color = AppColors.TextPrimary)
        Text(deviceLine, style = AppText.Meta, color = AppColors.TextFaint, modifier = Modifier.padding(top = 2.dp))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .background(AppColors.cream(0.04f), RoundedCornerShape(13.dp))
            .border(1.dp, AppColors.cream(0.14f), RoundedCornerShape(13.dp))
            .clickable { onNewChat() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Filled.Add, null, tint = AppColors.TextPrimary, modifier = Modifier.size(15.dp))
        Text("New chat", style = AppText.Label, color = AppColors.TextPrimary)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .clickable { onOpenSaved() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Filled.BookmarkBorder, null, tint = AppColors.TextBody, modifier = Modifier.size(15.dp))
        Text("Saved replies", style = AppText.Label, color = AppColors.TextBody, modifier = Modifier.weight(1f))
        Text("$savedCount", style = AppText.Meta, color = AppColors.TextFaint)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AppColors.TextFaint, modifier = Modifier.size(15.dp))
    }

    Text(
        "CHATS",
        style = AppText.MetaTiny.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = AppColors.TextFaint,
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 6.dp),
    )

    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(conversations, key = { it.id }) { conv ->
            ConversationRow(
                conv = conv,
                selected = conv.id == currentId,
                onSelect = { onSelectConversation(conv.id) },
                onExport = { onRequestExportConversation(conv.id) },
                onDelete = { onRequestDeleteConversation(conv) },
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(AppColors.cream(0.08f)))
        FooterNavItem(Icons.Filled.Tune, "Settings", onClick = onOpenSettings)
        FooterNavItem(Icons.Filled.Storage, "Models", trailingText = "$modelCount", onClick = onOpenModels)
        FooterNavItem(
            Icons.Filled.Science, "Experimental features",
            trailingDot = experimentalOn, onClick = onOpenExperimental,
        )
    }
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.cream(0.07f) else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(conv.title, style = AppText.Small.copy(fontSize = 13.sp), color = AppColors.TextBody,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            Box(
                Modifier.size(24.dp).clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.MoreHoriz, "More", tint = AppColors.TextFaint, modifier = Modifier.size(14.dp)) }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.width(160.dp).background(AppColors.SurfaceMenu),
            ) {
                MenuItem("Export chat", { menuOpen = false; onExport() },
                    leading = { Icon(Icons.Filled.Download, null, tint = AppColors.TextBody, modifier = Modifier.size(12.dp)) })
                MenuItem("Delete", { menuOpen = false; onDelete() }, color = AppColors.Danger,
                    leading = { Icon(Icons.Filled.DeleteOutline, null, tint = AppColors.Danger, modifier = Modifier.size(12.dp)) })
            }
        }
    }
}

@Composable
private fun FooterNavItem(
    icon: ImageVector,
    label: String,
    trailingText: String? = null,
    trailingDot: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = AppColors.TextBody, modifier = Modifier.size(15.dp))
        Text(label, style = AppText.Small.copy(fontSize = 13.sp), color = AppColors.TextBody, modifier = Modifier.weight(1f))
        if (trailingText != null) Text(trailingText, style = AppText.Meta, color = AppColors.TextFaint)
        if (trailingDot) Box(Modifier.size(6.dp).background(AccentBrush, RoundedCornerShape(3.dp)))
    }
}

// ---------------------------------------------------------------- Saved replies pane

@Composable
private fun ColumnScope.SavedRepliesPane(
    saved: List<SavedReply>,
    onBack: () -> Unit,
    onOpen: (SavedReply) -> Unit,
    onUnsave: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, sizeDp = 34, iconSizeDp = 18)
        Text("Saved replies", style = AppText.ScreenTitle, color = AppColors.TextPrimary)
    }
    if (saved.isEmpty()) {
        Text(
            "Nothing saved yet — tap the bookmark under a reply.",
            style = AppText.Small, color = AppColors.TextFaint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        )
    } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(saved, key = { it.messageId }) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        s.conversationTitle.uppercase(),
                        style = AppText.MetaTiny.copy(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                        color = AppColors.TextFaint,
                        modifier = Modifier.clickable { onOpen(s) },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.text, style = AppText.Small.copy(fontSize = 12.5.sp), color = AppColors.TextBody,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpen(s) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(24.dp).clickable {
                            clipboard.setText(AnnotatedString(s.text)); onToast("Copied to clipboard")
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ContentCopy, "Copy", tint = AppColors.TextMuted, modifier = Modifier.size(12.dp))
                        }
                        Box(Modifier.size(24.dp).clickable { onUnsave(s.messageId) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Bookmark, "Unsave", tint = AppColors.AccentB, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}
