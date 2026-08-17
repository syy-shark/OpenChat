package com.openchat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchat.domain.Avatar
import com.openchat.domain.AvatarDigest
import com.openchat.domain.Block
import com.openchat.domain.Byline
import com.openchat.domain.ChatHeader
import com.openchat.domain.ChatSession
import com.openchat.domain.Draft
import com.openchat.domain.Grouping
import com.openchat.domain.MessageBody
import com.openchat.domain.Progress
import com.openchat.domain.Row as TimelineRow
import com.openchat.ui.Face

private val BubbleGreen = Color(0xFF95EC69)
private val BubbleGrey = Color(0xFFF0F0F0)
private val SendGreen = Color(0xFF07C160)
private val FaceSize = 40.dp
private val CommonEmoji = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
    "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋",
    "😜", "🤪", "🤗", "🤔", "😎", "🥳", "😭", "😤",
    "👍", "👎", "👏", "🙏", "🔥", "❤️", "✨", "🎉",
)

@Composable
fun ChatScreen(
    session: ChatSession,
    bytesFor: (AvatarDigest) -> ByteArray?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val header by session.header.collectAsStateWithLifecycle()
    val timeline by session.timeline.collectAsStateWithLifecycle()
    var composer by rememberSaveable { mutableStateOf("") }
    var showEmoji by rememberSaveable { mutableStateOf(false) }

    BackHandler { onBack() }

    fun send() {
        val draft = Draft.of(composer) ?: return
        session.send(draft)
        composer = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Surface(color = Color(0xFFF7F7F7), shadowElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = when (val current = header) {
                        is ChatHeader.Direct -> current.peer.name.value
                        is ChatHeader.Group -> current.title.value
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            reverseLayout = true,
        ) {
            items(timeline.rows, key = { it.key.value }) { row ->
                when (row) {
                    is TimelineRow.Bubble -> Bubble(row, bytesFor)
                    is TimelineRow.DayBreak -> Text(row.day.toString())
                    is TimelineRow.Gap -> Unit
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = composer,
                onValueChange = { composer = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("消息") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { send() }),
            )
            IconButton(onClick = { showEmoji = !showEmoji }) {
                Icon(Icons.Filled.EmojiEmotions, contentDescription = "表情")
            }
            Button(
                onClick = ::send,
                colors = ButtonDefaults.buttonColors(containerColor = SendGreen),
                enabled = Draft.of(composer) != null,
            ) {
                Text("发送")
            }
        }
        if (showEmoji) {
            EmojiPicker(onPick = { composer += it })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiPicker(onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CommonEmoji.forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun Bubble(row: TimelineRow.Bubble, bytesFor: (AvatarDigest) -> ByteArray?) {
    val mine = row.byline is Byline.Mine
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            BurstFace(row.byline, row.grouping, bytesFor)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .then(
                    if (mine) Modifier.fillMaxWidth(0.78f)
                    else Modifier.weight(1f, fill = false),
                )
                .background(
                    color = if (mine) BubbleGreen else BubbleGrey,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            MessageBody(row.body)
            Text(
                text = row.stamp.text,
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelSmall,
            )
            if (row.progress != Progress.Sent) {
                Text(
                    text = if (row.progress == Progress.Sending) "发送中" else "发送失败",
                    color = Color(0xFF777777),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (mine) {
            Spacer(Modifier.width(8.dp))
            BurstFace(row.byline, row.grouping, bytesFor)
        }
    }
}

@Composable
private fun BurstFace(
    byline: Byline,
    grouping: Grouping,
    bytesFor: (AvatarDigest) -> ByteArray?,
) {
    if (grouping == Grouping.ContinuesBurst) {
        Spacer(Modifier.size(FaceSize))
        return
    }
    val avatar: Avatar
    val initial: Char
    when (byline) {
        is Byline.Mine -> {
            avatar = byline.avatar
            initial = initialOf(byline.avatar)
        }
        is Byline.Theirs -> {
            avatar = byline.avatar
            initial = byline.name.value.firstOrNull() ?: initialOf(byline.avatar)
        }
    }
    Face(
        avatar = avatar,
        initial = initial,
        size = FaceSize,
        bytesFor = bytesFor,
    )
}

private fun initialOf(avatar: Avatar): Char =
    when (avatar) {
        is Avatar.Initials -> avatar.of.value.firstOrNull() ?: '#'
        is Avatar.Image -> '#'
    }

@Composable
private fun MessageBody(body: MessageBody) {
    body.blocks.forEach { block ->
        when (block) {
            is Block.Prose -> Text(block.text.asPlainText())
            is Block.Code -> Monospace(block.source)
            is Block.Diagram -> Monospace(block.source.value)
            is Block.Chart -> Monospace(block.spec.title.orEmpty())
            is Block.Quote -> block.blocks.forEach { nested ->
                when (nested) {
                    is Block.Prose -> Text(nested.text.asPlainText())
                    is Block.Code -> Monospace(nested.source)
                    is Block.Diagram -> Monospace(nested.source.value)
                    is Block.Chart -> Monospace(nested.spec.title.orEmpty())
                    is Block.Quote -> Monospace(nested.toString())
                }
            }
        }
    }
}

@Composable
private fun Monospace(text: String) {
    Text(text = text, fontFamily = FontFamily.Monospace)
}
