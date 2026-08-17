package com.openchat.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openchat.domain.Avatar
import com.openchat.domain.ChatList
import com.openchat.domain.DirectConversation
import com.openchat.domain.DisplayName
import com.openchat.domain.GroupConversation
import com.openchat.domain.Preview
import com.openchat.ui.Face

private val GroupFaceName = checkNotNull(DisplayName.parse("#"))

@Composable
fun ChatListScreen(
    chatList: ChatList,
    contentPadding: PaddingValues,
    onOpenDirect: (DirectConversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "direct-header") {
            SectionHeader("私聊")
        }
        if (chatList.direct.isEmpty()) {
            item(key = "direct-empty") {
                EmptySection("暂无私聊")
            }
        } else {
            items(chatList.direct, key = { it.id.value }) { conversation ->
                DirectRow(conversation, onOpenDirect)
            }
        }

        item(key = "group-header") {
            SectionHeader("群聊")
        }
        if (chatList.groups.isEmpty()) {
            item(key = "group-empty") {
                EmptySection("暂无群聊")
            }
        } else {
            items(chatList.groups, key = { it.id.value }) { conversation ->
                GroupRow(conversation)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color(0xFF888888),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun EmptySection(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        color = Color(0xFF999999),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DirectRow(
    conversation: DirectConversation,
    onOpen: (DirectConversation) -> Unit,
) {
    ConversationRow(
        title = conversation.peer.name.value,
        subtitle = when (val preview = conversation.preview) {
            Preview.Empty -> "暂无消息"
            is Preview.Latest -> preview.text.value
        },
        onClick = { onOpen(conversation) },
        face = {
            Face(
                avatar = conversation.peer.avatar,
                initial = conversation.peer.name.value.firstOrNull() ?: '#',
                size = 48.dp,
            )
        },
    )
}

@Composable
private fun GroupRow(conversation: GroupConversation) {
    ConversationRow(
        title = conversation.title.value,
        subtitle = "${conversation.memberCount} 位成员",
        onClick = {},
        face = {
            Face(
                avatar = Avatar.Initials(GroupFaceName),
                initial = conversation.title.value.firstOrNull() ?: '#',
                size = 48.dp,
            )
        },
    )
}

@Composable
private fun ConversationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    face: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        face()
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                color = Color(0xFF999999),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 76.dp),
        color = Color(0xFFEDEDED),
    )
}
