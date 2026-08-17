package com.openchat.ui.shell

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchat.domain.Account
import com.openchat.domain.Accounts
import com.openchat.domain.AddContactOutcome
import com.openchat.domain.Avatar
import com.openchat.domain.Contact
import com.openchat.domain.Contacts
import com.openchat.domain.PeerRelation
import com.openchat.domain.ConversationId
import com.openchat.domain.DisplayName
import com.openchat.domain.Identity
import com.openchat.domain.PickedImage
import com.openchat.domain.RegisterOutcome
import com.openchat.domain.UserId
import com.openchat.domain.UserPair
import com.openchat.BuildConfig
import com.openchat.session.AppRelease
import com.openchat.session.OpenChatSession
import com.openchat.ui.Face
import com.openchat.ui.chat.ChatScreen
import com.openchat.update.installDownloadedApk
import com.openchat.ui.list.ChatListScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val WeChatGreen = Color(0xFF07C160)
private val Chrome = Color(0xFFF7F7F7)

private sealed interface ShellTab {
    val label: String
    val selectedIcon: ImageVector
    val unselectedIcon: ImageVector

    data object Chat : ShellTab {
        override val label = "聊天"
        override val selectedIcon = Icons.AutoMirrored.Filled.Chat
        override val unselectedIcon = Icons.AutoMirrored.Outlined.Chat
    }

    data object Contacts : ShellTab {
        override val label = "通讯录"
        override val selectedIcon = Icons.Filled.Contacts
        override val unselectedIcon = Icons.Outlined.Contacts
    }

    data object Me : ShellTab {
        override val label = "我"
        override val selectedIcon = Icons.Filled.Person
        override val unselectedIcon = Icons.Outlined.Person
    }
}

private val ShellTabs = listOf(ShellTab.Chat, ShellTab.Contacts, ShellTab.Me)

private sealed interface ContactSearch {
    data object Idle : ContactSearch
    data class Found(val contact: Contact, val relation: PeerRelation) : ContactSearch
    data object NotFound : ContactSearch
    data object Offline : ContactSearch
}

@Composable
fun OpenChatApp(session: OpenChatSession) {
    val account by session.accounts.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = openChatColors()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (account) {
                    Account.SignedOut -> RegisterScreen(session.accounts)
                    is Account.SignedIn -> SignedInShell(session)
                }
                UpdatePrompt(session)
            }
        }
    }
}

private sealed interface UpdatePromptState {
    data object Hidden : UpdatePromptState
    data class Ready(val release: AppRelease) : UpdatePromptState
    data class Downloading(val release: AppRelease) : UpdatePromptState
    data class Failed(val release: AppRelease) : UpdatePromptState
    data class Downloaded(val release: AppRelease, val apk: File) : UpdatePromptState
}

@Composable
private fun UpdatePrompt(session: OpenChatSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<UpdatePromptState>(UpdatePromptState.Hidden) }

    LaunchedEffect(Unit) {
        val release = runCatching { session.newerRelease(BuildConfig.VERSION_CODE) }.getOrNull()
            ?: return@LaunchedEffect
        prompt = UpdatePromptState.Ready(release)
    }

    val current = prompt
    if (current is UpdatePromptState.Hidden) return
    val release = when (current) {
        is UpdatePromptState.Ready -> current.release
        is UpdatePromptState.Downloading -> current.release
        is UpdatePromptState.Failed -> current.release
        is UpdatePromptState.Downloaded -> current.release
        UpdatePromptState.Hidden -> return
    }
    AlertDialog(
        onDismissRequest = {
            if (prompt !is UpdatePromptState.Downloading) {
                prompt = UpdatePromptState.Hidden
            }
        },
        title = { Text("发现新版本") },
        text = {
            Text(
                when (current) {
                    is UpdatePromptState.Downloading -> "正在下载，完成后会打开系统安装页"
                    is UpdatePromptState.Failed -> "下载失败，请稍后重试"
                    is UpdatePromptState.Downloaded -> "OpenChat ${release.versionName} 已下载，可以安装"
                    is UpdatePromptState.Ready -> "OpenChat ${release.versionName} 可以安装"
                    UpdatePromptState.Hidden -> ""
                },
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    when (val state = prompt) {
                        is UpdatePromptState.Downloaded -> installDownloadedApk(context, state.apk)
                        is UpdatePromptState.Downloading, UpdatePromptState.Hidden -> Unit
                        is UpdatePromptState.Ready, is UpdatePromptState.Failed -> {
                            prompt = UpdatePromptState.Downloading(release)
                            scope.launch {
                                val apk = File(context.cacheDir, "updates/openchat.apk")
                                val ok = runCatching { session.downloadUpdate(apk) }.isSuccess
                                prompt = if (ok) {
                                    UpdatePromptState.Downloaded(release, apk)
                                } else {
                                    UpdatePromptState.Failed(release)
                                }
                                if (ok) installDownloadedApk(context, apk)
                            }
                        }
                    }
                },
                enabled = prompt !is UpdatePromptState.Downloading,
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
            ) {
                Text(if (prompt is UpdatePromptState.Downloading) "下载中" else "立即安装")
            }
        },
        dismissButton = {
            if (prompt !is UpdatePromptState.Downloading) {
                Button(
                    onClick = { prompt = UpdatePromptState.Hidden },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF666666)),
                ) {
                    Text("稍后")
                }
            }
        },
    )
}

@Composable
private fun SignedInShell(session: OpenChatSession) {
    val account by session.accounts.state.collectAsStateWithLifecycle()
    val identity = (account as? Account.SignedIn)?.me ?: return
    var selectedTab: ShellTab by remember { mutableStateOf(ShellTab.Chat) }
    var openConversation by remember { mutableStateOf<ConversationId?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { runCatching { session.refreshInbox() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    openConversation?.let { conversation ->
        DirectChatRoute(
            session = session,
            conversation = conversation,
            onBack = { openConversation = null },
        )
        return
    }

    Scaffold(
        topBar = { ShellHeader(selectedTab.label) },
        bottomBar = {
            NavigationBar(containerColor = Chrome) {
                ShellTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = WeChatGreen,
                            selectedTextColor = WeChatGreen,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        when (selectedTab) {
            ShellTab.Chat -> {
                LaunchedEffect(Unit) { runCatching { session.refreshInbox() } }
                val chats by session.chats.list.collectAsStateWithLifecycle()
                ChatListScreen(
                    chatList = chats,
                    contentPadding = contentPadding,
                    onOpenDirect = { openConversation = it.id },
                )
            }

            ShellTab.Contacts -> {
                LaunchedEffect(Unit) { runCatching { session.refreshInbox() } }
                ContactsScreen(
                    contacts = session.contacts,
                    identity = identity,
                    contentPadding = contentPadding,
                    onOpenChat = { contact ->
                        UserPair.of(identity.id, contact.id)
                            ?.let(ConversationId::direct)
                            ?.let { openConversation = it }
                    },
                )
            }
            ShellTab.Me -> MeScreen(session, identity, contentPadding)
        }
    }
}

@Composable
private fun DirectChatRoute(
    session: OpenChatSession,
    conversation: ConversationId,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chat = remember(conversation) { session.chats.session(conversation, scope) }
    ChatScreen(session = chat, bytesFor = session::bytesFor, onBack = onBack)
}

@Composable
private fun ShellHeader(title: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = Chrome,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun RegisterScreen(accounts: Accounts) {
    var publicId by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(register: Boolean) {
        val id = UserId.parse(publicId)
        val name = if (register) DisplayName.parse(displayName) else null
        if (id == null || (register && name == null)) {
            error = if (register) {
                "请输入 4 到 20 位小写 ID，并填写显示名称"
            } else {
                "请输入 4 到 20 位小写 ID"
            }
            return
        }
        scope.launch {
            val outcome = if (register) {
                accounts.register(id, checkNotNull(name))
            } else {
                accounts.login(id)
            }
            error = when (outcome) {
                is RegisterOutcome.Registered -> null
                RegisterOutcome.IdTaken -> "这个 ID 已被使用"
                RegisterOutcome.Offline -> if (register) "暂时无法注册" else "ID 不存在或服务器不可用"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.ime)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "注册 OpenChat",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "设置公开 ID 和显示名称",
            color = Color(0xFF777777),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = publicId,
            onValueChange = { publicId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("公开 ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("显示名称") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit(register = true) }),
        )
        error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { submit(register = true) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("注册")
            }
            Button(
                onClick = { submit(register = false) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("登录")
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    contacts: Contacts,
    identity: Identity,
    contentPadding: PaddingValues,
    onOpenChat: (Contact) -> Unit,
) {
    val all by contacts.all.collectAsStateWithLifecycle()
    val incoming by contacts.incoming.collectAsStateWithLifecycle()
    var publicId by rememberSaveable { mutableStateOf("") }
    var search: ContactSearch by remember { mutableStateOf(ContactSearch.Idle) }
    var outgoingIds by remember { mutableStateOf(setOf<UserId>()) }
    var showingIncoming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun relationOf(contact: Contact): PeerRelation = when {
        all.any { it.id == contact.id } -> PeerRelation.Friend
        contact.id in outgoingIds -> PeerRelation.Outgoing
        else -> PeerRelation.Stranger
    }

    fun searchContact() {
        val id = UserId.parse(publicId)
        if (id == null) {
            Toast.makeText(context, "请输入有效的公开 ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (UserPair.of(identity.id, id) == null) {
            Toast.makeText(context, "不能添加自己", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            search = try {
                val contact = contacts.lookup(id)
                if (contact == null) {
                    ContactSearch.NotFound
                } else {
                    ContactSearch.Found(contact, relationOf(contact))
                }
            } catch (_: Exception) {
                ContactSearch.Offline
            }
        }
    }

    fun updateSearch(contact: Contact, relation: PeerRelation) {
        val current = search
        if (current is ContactSearch.Found && current.contact.id == contact.id) {
            search = ContactSearch.Found(contact, relation)
        }
    }

    fun addContact(contact: Contact) {
        scope.launch {
            val message = when (val outcome = contacts.add(contact.id)) {
                is AddContactOutcome.Added -> {
                    updateSearch(outcome.contact, PeerRelation.Friend)
                    "已添加"
                }
                is AddContactOutcome.AlreadyKnown -> {
                    updateSearch(outcome.contact, PeerRelation.Friend)
                    "已在通讯录"
                }
                is AddContactOutcome.Requested -> {
                    outgoingIds = outgoingIds + outcome.contact.id
                    updateSearch(outcome.contact, PeerRelation.Outgoing)
                    "已发送好友申请"
                }
                is AddContactOutcome.AlreadyRequested -> {
                    outgoingIds = outgoingIds + outcome.contact.id
                    updateSearch(outcome.contact, PeerRelation.Outgoing)
                    "已发送过申请，等待对方通过"
                }
                AddContactOutcome.NoSuchUser -> {
                    search = ContactSearch.NotFound
                    "找不到该用户"
                }
                AddContactOutcome.Offline -> {
                    search = ContactSearch.Offline
                    "服务器不可用"
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.ime),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = publicId,
                onValueChange = {
                    publicId = it
                    search = ContactSearch.Idle
                },
                modifier = Modifier.weight(1f),
                label = { Text("输入公开 ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { searchContact() }),
            )
            Button(
                onClick = ::searchContact,
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
            ) {
                Text("搜索")
            }
        }
        when (val result = search) {
            ContactSearch.Idle -> Unit
            is ContactSearch.Found -> SearchResultCard(
                result = result,
                onAdd = { addContact(result.contact) },
                onOpenChat = { onOpenChat(result.contact) },
            )
            ContactSearch.NotFound -> SearchMessage("找不到该用户")
            ContactSearch.Offline -> SearchMessage("服务器不可用")
        }
        NewFriendsRow(
            count = incoming.size,
            onClick = { showingIncoming = !showingIncoming },
        )
        HorizontalDivider(color = Color(0xFFEDEDED))
        if (showingIncoming) {
            IncomingList(incoming, onAccept = ::addContact)
        } else if (all.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无联系人", color = Color(0xFF999999))
            }
        } else {
            ContactList(all, onOpenChat)
        }
    }
}

@Composable
private fun SearchResultCard(
    result: ContactSearch.Found,
    onAdd: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(result.contact.name.value, fontWeight = FontWeight.Medium)
            Text(
                "OpenChat ID：${result.contact.id.value}",
                color = Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when (result.relation) {
            PeerRelation.Stranger -> Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
            ) {
                Text("添加好友")
            }
            PeerRelation.Outgoing -> Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
            ) {
                Text("等待验证")
            }
            PeerRelation.Friend -> Button(
                onClick = onOpenChat,
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
            ) {
                Text("发消息")
            }
        }
    }
}

@Composable
private fun NewFriendsRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("新的朋友", fontWeight = FontWeight.Medium)
            Text(
                text = if (count == 0) "暂无新的朋友" else count.toString(),
                color = Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFFB2B2B2),
        )
    }
}

@Composable
private fun IncomingList(incoming: List<Contact>, onAccept: (Contact) -> Unit) {
    if (incoming.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无新的朋友", color = Color(0xFF999999))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(incoming, key = { it.id.value }) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Face(
                    avatar = contact.avatar,
                    initial = contact.name.value.firstOrNull() ?: '#',
                    size = 48.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(contact.name.value, fontWeight = FontWeight.Medium)
                    Text(
                        text = "OpenChat ID：${contact.id.value}",
                        color = Color(0xFF777777),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { onAccept(contact) },
                    colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
                ) {
                    Text("接受")
                }
            }
            HorizontalDivider(color = Color(0xFFEDEDED))
        }
    }
}

@Composable
private fun SearchMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color(0xFF777777),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts, key = { it.id.value }) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(contact) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Face(
                    avatar = contact.avatar,
                    initial = contact.name.value.firstOrNull() ?: '#',
                    size = 48.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(text = contact.name.value, fontWeight = FontWeight.Medium)
                    Text(
                        text = "OpenChat ID：${contact.id.value}",
                        color = Color(0xFF777777),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider(color = Color(0xFFEDEDED))
        }
    }
}

@Composable
private fun MeScreen(
    session: OpenChatSession,
    identity: Identity,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                val picked = bytes?.let(PickedImage::of)
                if (picked == null) {
                    Toast.makeText(context, "请选择 JPG、PNG 或 WebP 图片", Toast.LENGTH_SHORT).show()
                } else {
                    session.accounts.setAvatar(picked)
                }
            }
        }
    }
    val openPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = openPicker)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarView(session, identity)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = identity.name.value,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "OpenChat ID：${identity.id.value}",
                    color = Color(0xFF777777),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFFB2B2B2),
            )
        }
        HorizontalDivider(color = Color(0xFFEDEDED))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { confirmSignOut = true }
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("退出账号", style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出账号") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSignOut = false
                        session.accounts.signOut()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen),
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                Button(
                    onClick = { confirmSignOut = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF666666),
                    ),
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun AvatarView(session: OpenChatSession, identity: Identity) {
    Box(modifier = Modifier.size(72.dp)) {
        val digest = (identity.avatar as? Avatar.Image)?.digest
        val imageBitmap = remember(digest) {
            digest?.let { session.bytesFor(it) }?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        }
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(WeChatGreen, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = identity.name.value.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(24.dp)
                .background(Color.White, CircleShape)
                .padding(2.dp)
                .background(WeChatGreen, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = "更换头像",
                modifier = Modifier.size(14.dp),
                tint = Color.White,
            )
        }
    }
}

private fun openChatColors(): ColorScheme = lightColorScheme(
    primary = WeChatGreen,
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Chrome,
)
