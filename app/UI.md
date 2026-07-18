# Context & Requirements: WhatsApp Main Screen Jetpack Compose Layout

I am building a high-performance, responsive WhatsApp Clone UI in Android Studio using Jetpack Compose and modern Android Architecture components.

I need you to act as a Senior Android Engineer and generate clean, highly decoupled, and production-ready Jetpack Compose code based on the structural blueprint outlined below.

---

## 1. Architectural Blueprint
Please generate the UI files according to this structural specification:

ui/
├── theme/
│   ├── Color.kt        # WhatsApp Material 3 Palette (Teal/Green variants)
│   └── Theme.kt        # Dynamic dark/light material configuration
└── components/
├── MainDashboard.kt# Root Scaffold container managing TopBar, Pager, and FAB
├── TopChatBar.kt    # TopAppBar component with WhatsApp styling & icons
└── ChatListScreen.kt# Scrollable list containing individual chat row components

---

## 2. Design Specifics & Component States

### Color Palette (Material 3)
- **Light Theme:** Primary (`#008069` Deep Teal), Secondary (`#25D366` Light Green), Background (`#FFFFFF`), Surface (`#FFFFFF`).
- **Dark Theme:** Primary (`#1F2C34` Dark Gray-Green), Secondary (`#00A884` Teal Green), Background (`#111B21` Dark Canvas), Surface (`#202C33` Bubble Background).

### MainDashboard.kt
- Implement a `Scaffold` container.
- Connect a `HorizontalPager` containing 3 pages: "Chats", "Updates", and "Calls".
- Link the pager to a `ScrollableTabRow` underneath the TopAppBar so that tabs sync smoothly with swiping gestures.
- Add a floating action button (`FloatingActionButton`) at the bottom right corner with a message chat bubble icon.

### TopChatBar.kt
- Implement a modern `TopAppBar`.
- Left-aligned text: "WhatsApp" styled with `FontWeight.Bold` and size `22.sp`.
- Right-aligned actions: Camera icon, Search magnifier icon, and Vertical three-dot overflow menu icon.

### ChatListScreen.kt & ChatRow Component
- Create a `LazyColumn` state that cleanly renders individual chat histories.
- Each chat item row needs to contain:
    - An async or clip-bounded circular avatar image (`48.dp` wide).
    - A core `Column` displaying the sender's display name (`FontWeight.SemiBold`) and the truncated preview of the last message text.
    - A right-aligned `Column` showing the timestamp string (e.g., "10:45 AM") and a green badge indicator displaying the unread count if the counter is greater than zero.

---

## 3. UI State Definition
Please use the following Kotlin data model structure to bind state data to the Composables:

```kotlin
data class ChatItemState(
    val id: String,
    val senderName: String,
    val lastMessage: String,
    val timestamp: String,
    val avatarUrl: String?,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false
)

4. Code Generation Rules
Compose Previews: Provide a distinct @Preview block with realistic dummy data for both Light Mode and Dark Mode configurations so I can visualize the layout inside the Split view.

Accessibility: Ensure all icon actions and image components include clean, clear contentDescription properties.

Modifiers Execution: Always externalize a structural Modifier component argument to allow parent callers to tweak paddings or custom layout configurations.