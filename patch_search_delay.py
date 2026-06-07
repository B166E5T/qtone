import sys
import os

path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "MultiviewScreen.kt")

if not os.path.exists(path):
    print("ERROR: Could not find " + path)
    print("Make sure you run this script from the qtone project root folder.")
    sys.exit(1)

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

print("Read MultiviewScreen.kt (" + str(len(content)) + " chars)")

# ── STEP 1: Add showSearch state + LaunchedEffect ──
# Insert right after the 'editing' state declaration
old_state = '    var editing by remember { mutableStateOf(false) }'
new_state = """    var editing by remember { mutableStateOf(false) }

    // Delay search field appearance by 250ms so the categories column
    // gets initial focus when the picker opens. Without this, the search
    // Surface is the first focusable element and attracts focus + keyboard.
    var showSearch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        showSearch = true
    }"""

if old_state not in content:
    print("ERROR: Could not find editing state declaration")
    sys.exit(1)
content = content.replace(old_state, new_state, 1)
print("Step 1 OK: showSearch state added")

# ── STEP 2: Replace the broken Box search field with the original Surface, wrapped in showSearch ──
# Find the current broken search code (Box with searchFieldFocused)
old_search_block = """                if (editing) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        label = { Text("Search channels") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            editing = false
                            keyboard?.hide()
                            focusManager.clearFocus(force = true)
                        }),
                        modifier = Modifier
                            .width(280.dp)
                            .focusRequester(searchFocusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = QtoneColors.Text,
                            fontSize = 14.sp
                        )
                    )
                } else {
                    var searchFieldFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF15151B))
                            .border(
                                if (searchFieldFocused) 2.dp else 1.dp,
                                if (searchFieldFocused) Color.White else Color(0x44FFFFFF),
                                RoundedCornerShape(8.dp)
                            )
                            .focusable()
                            .onFocusChanged {
                                searchFieldFocused = it.isFocused
                                if (it.isFocused) keyboard?.hide()
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionCenter ||
                                     event.key == Key.Enter ||
                                     event.key == Key.NumPadEnter)
                                ) {
                                    editing = true
                                    true
                                } else false
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { editing = true })
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "Search channels\u2026" else searchQuery,
                            color = if (searchQuery.isBlank()) QtoneColors.Muted else QtoneColors.Text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }"""

new_search_block = """                if (showSearch) {
                    if (editing) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            label = { Text("Search channels") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                editing = false
                                keyboard?.hide()
                                focusManager.clearFocus(force = true)
                            }),
                            modifier = Modifier
                                .width(280.dp)
                                .focusRequester(searchFocusRequester),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = QtoneColors.Text,
                                fontSize = 14.sp
                            )
                        )
                    } else {
                        Surface(
                            onClick = { editing = true },
                            modifier = Modifier
                                .width(280.dp)
                                .height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF15151B),
                            contentColor = QtoneColors.Text,
                            border = BorderStroke(1.dp, Color(0x44FFFFFF))
                        ) {
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (searchQuery.isBlank()) "Search channels\u2026" else searchQuery,
                                    color = if (searchQuery.isBlank()) QtoneColors.Muted else QtoneColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }"""

if old_search_block not in content:
    print("ERROR: Could not find the current search block to replace")
    print("The file may have been modified differently than expected.")
    sys.exit(1)
content = content.replace(old_search_block, new_search_block, 1)
print("Step 2 OK: Search field restored to Surface + wrapped in showSearch")

# ── Write result ──
with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("")
print("=" * 50)
print("Search fix applied!")
print("")
print("How it works:")
print("- Picker opens -> categories get focus (no keyboard)")
print("- After 250ms, search field appears in the header")
print("- User can navigate to it with D-pad and press OK")
print("- Keyboard only opens on explicit OK press")
print("- Proper focus visuals (Surface handles this natively)")
print("=" * 50)
