package cn.huanong.canteen

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.huanong.canteen.data.*
import cn.huanong.canteen.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(248, 249, 246)
        window.navigationBarColor = android.graphics.Color.rgb(248, 249, 246)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { HuaNongApp(viewModel) }
    }
}

private val Leaf = Color(0xFF31845D)
private val LeafDark = Color(0xFF176342)
private val Canvas = Color(0xFFF6F8F5)
private val Ink = Color(0xFF202720)
private val Muted = Color(0xFF7C867E)
private val Line = Color(0xFFE8ECE7)

@Composable
private fun HuaNongApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var addShop by remember { mutableStateOf(false) }
    var addDish by remember { mutableStateOf(false) }
    var activeDish by remember { mutableStateOf<Dish?>(null) }
    var photoTarget by remember { mutableStateOf(PhotoTarget.NONE) }
    var shopImage by remember { mutableStateOf<String?>(null) }
    var dishImage by remember { mutableStateOf<String?>(null) }
    var backupDialog by remember { mutableStateOf(false) }
    var accountDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { context.contentResolver.openOutputStream(uri)?.let { vm.exportBackup(it) } ?: error("无法创建备份文件") }
            android.widget.Toast.makeText(context, if (result.isSuccess) "备份已保存" else "备份失败：${result.exceptionOrNull()?.message ?: "请重试"}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val orderExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { context.contentResolver.openOutputStream(uri)?.let { vm.exportOrdersCsv(it) } ?: error("无法创建导出文件") }
            android.widget.Toast.makeText(context, if (result.isSuccess) "订单 CSV 已导出" else "导出失败：${result.exceptionOrNull()?.message ?: "请重试"}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { context.contentResolver.openInputStream(uri)?.let { vm.importBackup(it) } ?: error("无法读取备份文件") }
            android.widget.Toast.makeText(context, if (result.isSuccess) "菜单数据和图片已恢复" else "恢复失败：${result.exceptionOrNull()?.message ?: "备份文件无效"}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val folder = File(context.filesDir, "menu-images").apply { mkdirs() }
                val target = File(folder, "${UUID.randomUUID()}.img")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                target.absolutePath
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (photoTarget == PhotoTarget.SHOP) shopImage = saved else if (photoTarget == PhotoTarget.DISH) dishImage = saved
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf, onPrimary = Color.White, background = Canvas, surface = Color.White)) {
        Scaffold(containerColor = Canvas, bottomBar = {
            if (state.account != null && state.screen != Screen.ORDERS) BottomBar(
                cartCount = state.snapshot.cart.sumOf { it.quantity },
                total = state.snapshot.cart.sumOf { it.unitPriceCents * it.quantity },
                selected = state.screen == Screen.CART,
                onCart = { vm.show(Screen.CART) },
                onOrders = { vm.show(Screen.ORDERS) }
            )
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                if (state.account == null) {
                    AuthScreen(busy = state.authBusy, error = state.authError, onSubmit = vm::authenticate)
                } else {
                AnimatedContent(
                    targetState = state.screen to state.selectedShopId,
                    transitionSpec = { (fadeIn(tween(170)) + slideInHorizontally(tween(170)) { it / 28 }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 36 }) },
                    label = "page-transition"
                ) { route -> when (route.first) {
                    Screen.MENU -> if (route.second == null) {
                        HallHome(state, onSelectHall = vm::selectHall, onOpenShop = vm::openShop, onAddShop = { addShop = true }, onDeleteShop = vm::deleteShop, onOrders = { vm.show(Screen.ORDERS) }, onBackup = { backupDialog = true }, onAccount = { accountDialog = true })
                    } else {
                        val shop = state.snapshot.shops.firstOrNull { it.id == route.second }
                        if (shop != null) ShopMenu(
                            shop = shop,
                            dishes = state.snapshot.dishes.filter { it.shopId == shop.id },
                            onBack = vm::backToHalls,
                            onAddDish = { dishImage = null; addDish = true },
                            onAdd = { dish, note ->
                                val old = state.snapshot.cart.firstOrNull { it.dishId == dish.id && it.note == note }
                                vm.adjust(CartLine(dish.id, shop.id, shop.name, dish.name, dish.priceCents, old?.quantity ?: 0, note), 1)
                            },
                            onDetail = { activeDish = it }
                        ) else LaunchedEffect(state.selectedShopId) { vm.backToHalls() }
                    }
                    Screen.CART -> CartScreen(state.snapshot.cart, placingOrder = state.placingOrder, onQuantity = vm::adjust, onCheckout = vm::placeOrder, onBrowse = { vm.show(Screen.MENU) })
                    Screen.ORDERS -> OrdersScreen(
                        orders = state.orders, stats = state.orderStats, period = state.orderPeriod,
                        loading = state.ordersLoading, hasMore = state.ordersHasMore,
                        onPeriod = vm::setOrderPeriod, onLoadMore = vm::loadMoreOrders,
                        onOpen = vm::openOrder, onExport = { orderExportPicker.launch("华农食堂-订单记录.csv") },
                        onBack = { vm.show(Screen.MENU) }
                    )
                } }
                }
                if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Leaf) }
            }
        }
    }

    if (accountDialog && state.account != null) AlertDialog(
        onDismissRequest = { accountDialog = false },
        title = { Text("云端账号") },
        text = { Column {
            Text(state.account!!.email, color = Ink, fontWeight = FontWeight.Medium)
            Text("数据状态：${state.cloudStatus}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            state.authError?.let { Text(it, color = Color(0xFFB3261E), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        } },
        confirmButton = { TextButton(onClick = { accountDialog = false; vm.syncNow() }) { Text("立即同步", color = Leaf) } },
        dismissButton = { TextButton(onClick = { accountDialog = false; vm.logout() }) { Text("退出登录", color = Muted) } },
        containerColor = Color.White
    )

    if (backupDialog) AlertDialog(
        onDismissRequest = { backupDialog = false },
        title = { Text("菜单数据备份") },
        text = { Text("导出文件包含店铺、菜品和图片。卸载重装后，可从备份文件恢复。系统云备份也已启用。") },
        confirmButton = { TextButton(onClick = { backupDialog = false; exportPicker.launch("华农食堂-菜单备份.zip") }) { Text("导出备份", color = Leaf) } },
        dismissButton = { TextButton(onClick = { backupDialog = false; importPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("恢复备份") } },
        containerColor = Color.White
    )

    state.orderDetail?.let { detail -> OrderDetailDialog(detail, onDismiss = vm::closeOrderDetail) }
    if (state.detailLoading) AlertDialog(onDismissRequest = vm::closeOrderDetail, confirmButton = {}, title = { Text("订单详情") }, text = { CircularProgressIndicator(color = Leaf) }, containerColor = Color.White)

    if (addShop) ShopEditorDialog(
        hallName = state.snapshot.halls.firstOrNull { it.id == state.selectedHallId }?.name.orEmpty(),
        imagePath = shopImage,
        onPickImage = { photoTarget = PhotoTarget.SHOP; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onDismiss = { addShop = false; shopImage = null },
        onSave = { name, desc -> vm.addShop(state.selectedHallId, name, desc, shopImage); addShop = false; shopImage = null }
    )
    if (addDish) {
        val selected = state.snapshot.shops.firstOrNull { it.id == state.selectedShopId }
        if (selected != null) DishEditorDialog(
            imagePath = dishImage,
            onPickImage = { photoTarget = PhotoTarget.DISH; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDismiss = { addDish = false; dishImage = null },
            onSave = { name, category, desc, price -> vm.addDish(selected.id, name, category, desc, price, dishImage); addDish = false; dishImage = null }
        )
    }
    activeDish?.let { dish -> DishDetailDialog(dish, onDismiss = { activeDish = null }, onAdd = { note ->
        val shop = state.snapshot.shops.firstOrNull { it.id == dish.shopId } ?: return@DishDetailDialog
        val old = state.snapshot.cart.firstOrNull { it.dishId == dish.id && it.note == note }
        vm.adjust(CartLine(dish.id, shop.id, shop.name, dish.name, dish.priceCents, old?.quantity ?: 0, note), 1)
        activeDish = null
    }) }
}

private enum class PhotoTarget { NONE, SHOP, DISH }

@Composable
private fun HallHome(
    state: MenuUiState,
    onSelectHall: (Long) -> Unit,
    onOpenShop: (Long) -> Unit,
    onAddShop: () -> Unit,
    onDeleteShop: (Long) -> Unit,
    onOrders: () -> Unit,
    onBackup: () -> Unit,
    onAccount: () -> Unit
) {
    val hall = state.snapshot.halls.firstOrNull { it.id == state.selectedHallId }
    var pendingDelete by remember { mutableStateOf<Shop?>(null) }
    var previewShop by remember { mutableStateOf<Shop?>(null) }
    Column(Modifier.fillMaxSize()) {
        HeaderBar("华农食堂", "校园点单 · ${state.cloudStatus}", onOrders = onOrders, onBackup = onBackup, onAccount = onAccount)
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("今天想吃点什么？", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("按食堂浏览，也可以看看外卖商家", fontSize = 13.sp, color = Muted)
        }
        Row(Modifier.weight(1f).padding(top = 8.dp)) {
            LazyColumn(Modifier.width(98.dp).fillMaxHeight().background(Color(0xFFF0F3EF)), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(state.snapshot.halls, key = { it.id }) { item ->
                    val chosen = item.id == state.selectedHallId
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 8.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (chosen) Color.White else Color.Transparent).clickable { onSelectHall(item.id) }
                            .padding(horizontal = 8.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (chosen) Box(Modifier.width(3.dp).height(18.dp).clip(CircleShape).background(Leaf))
                        Spacer(Modifier.width(if (chosen) 6.dp else 9.dp))
                        Text(item.name, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, color = if (chosen) LeafDark else Muted, maxLines = 2)
                    }
                }
            }
            val shops = state.snapshot.shops.filter { it.hallId == state.selectedHallId }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 12.dp, end = 15.dp, top = 7.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(hall?.name ?: "食堂", fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold)
                            Text("${shops.size} 家店铺", fontSize = 12.sp, color = Muted)
                        }
                        TextButton(onClick = onAddShop) { Text("＋ 添加店铺", color = Leaf, fontSize = 12.sp) }
                    }
                }
                if (shops.isEmpty()) item { EmptyCard("这里还没有店铺", "添加店名和图片，开始整理菜单", "＋ 添加店铺", onAddShop) }
                items(shops, key = { it.id }) { shop ->
                    ShopCard(shop, state.snapshot.dishes.count { it.shopId == shop.id }, onClick = { onOpenShop(shop.id) }, onPreview = { previewShop = shop }, onDelete = { pendingDelete = shop })
                }
            }
        }
    }
    pendingDelete?.let { shop ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除“${shop.name}”？") },
            text = { Text("店铺及其菜单会从本机移除；已保存的历史订单不会改变。") },
            confirmButton = { TextButton(onClick = { onDeleteShop(shop.id); pendingDelete = null }) { Text("删除", color = Color(0xFFB64035)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            containerColor = Color.White
        )
    }
    previewShop?.let { shop -> ImagePreviewDialog(shop.name, shop.imagePath, "🏪", onDismiss = { previewShop = null }) }
}

@Composable
private fun ShopCard(shop: Shop, dishCount: Int, onClick: () -> Unit, onPreview: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuImage(shop.imagePath, "🍲", Modifier.size(74.dp).clickable(onClick = onPreview))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 3.dp)) {
            Text(shop.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(shop.description.ifBlank { "欢迎选购 · $dishCount 款菜品" }, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("$dishCount 款菜品  ·  进入菜单 ›", fontSize = 11.sp, color = Leaf)
        }
        TextButton(onClick = onDelete) { Text("删除", fontSize = 11.sp, color = Muted) }
    }
}

@Composable
private fun ShopMenu(shop: Shop, dishes: List<Dish>, onBack: () -> Unit, onAddDish: () -> Unit, onAdd: (Dish, String) -> Unit, onDetail: (Dish) -> Unit) {
    var category by remember(shop.id) { mutableStateOf("全部") }
    val categories = listOf("全部") + dishes.map { it.category }.distinct()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable(onClick = onBack).padding(end = 14.dp), fontSize = 31.sp, color = Ink)
            Column(Modifier.weight(1f)) {
                Text(shop.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(shop.description.ifBlank { "店铺菜单" }, fontSize = 12.sp, color = Muted, maxLines = 1)
            }
            TextButton(onClick = onAddDish) { Text("＋ 菜品", color = Leaf) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color.White).padding(horizontal = 14.dp, vertical = 4.dp)) {
            categories.forEach { item ->
                val selected = category == item
                Text(item, Modifier.padding(end = 8.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) Color(0xFFE8F3EC) else Color.Transparent)
                    .clickable { category = item }.padding(horizontal = 15.dp, vertical = 9.dp), color = if (selected) LeafDark else Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        val visible = if (category == "全部") dishes else dishes.filter { it.category == category }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyCard("还没有菜品", "添加菜名、分类、价格和图片", "＋ 添加菜品", onAddDish) }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            items(visible, key = { it.id }) { dish -> DishRow(dish, onAdd = { onAdd(dish, "") }, onClick = { onDetail(dish) }) }
            item { OutlinedButton(onClick = onAddDish, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("＋ 添加菜品", color = Leaf) } }
        }
    }
}

@Composable
private fun DishRow(dish: Dish, onAdd: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onClick).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuImage(dish.imagePath, "🍱", Modifier.size(88.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dish.name, fontSize = 15.sp, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(dish.description.ifBlank { dish.category }, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(dish.priceCents.asPrice(), fontSize = 16.sp, color = LeafDark, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        FilledIconButton(onClick = onAdd, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Leaf)) { Text("+", color = Color.White, fontSize = 21.sp) }
    }
}

@Composable
private fun DishDetailDialog(dish: Dish, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var note by remember { mutableStateOf("正常") }
    val choices = listOf("正常", "少辣", "不吃葱")
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        Button(onClick = { onAdd(if (note == "正常") "" else note) }, colors = ButtonDefaults.buttonColors(containerColor = Leaf)) { Text("加入购物车 · ${dish.priceCents.asPrice()}") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } }, title = { Text(dish.name, fontWeight = FontWeight.Bold) }, text = {
        Column {
            MenuImage(dish.imagePath, "🍱", Modifier.fillMaxWidth().height(150.dp))
            Spacer(Modifier.height(10.dp)); Text(dish.description.ifBlank { "选择口味备注" }, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp)); Text("口味备注", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 8.dp)) {
                choices.forEach { choice -> FilterChip(selected = note == choice, onClick = { note = choice }, label = { Text(choice) }) }
            }
        }
    }, containerColor = Color.White)
}

@Composable
private fun CartScreen(lines: List<CartLine>, placingOrder: Boolean, onQuantity: (CartLine, Int) -> Unit, onCheckout: () -> Unit, onBrowse: () -> Unit) {
    val total = lines.sumOf { it.unitPriceCents * it.quantity }
    Column(Modifier.fillMaxSize()) {
        HeaderBar("购物车", "跨店合并 · 按店铺分组")
        if (lines.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyCard("购物车还是空的", "先去选几样喜欢的菜", "去逛逛", onBrowse) }
        else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.groupBy { it.shopId }.forEach { (_, shopLines) ->
                    item(key = "shop-${shopLines.first().shopId}") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                            Text(shopLines.first().shopName, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Line)
                            shopLines.forEach { line ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.dishName, color = Ink, fontSize = 14.sp)
                                        if (line.note.isNotBlank()) Text(line.note, color = Muted, fontSize = 11.sp)
                                        Text(line.unitPriceCents.asPrice(), color = LeafDark, fontSize = 12.sp)
                                    }
                                    QuantityControl(line.quantity, onMinus = { onQuantity(line, -1) }, onPlus = { onQuantity(line, 1) })
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 15.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("合计", color = Ink, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f)); Text(total.asPrice(), color = LeafDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Spacer(Modifier.height(11.dp))
                Button(onClick = onCheckout, enabled = !placingOrder, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
                    if (placingOrder) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)); Text("正在保存订单…", fontSize = 15.sp) }
                    else Text("提交订单", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun OrdersScreen(
    orders: List<OrderSummary>, stats: OrderStats, period: OrderPeriod, loading: Boolean, hasMore: Boolean,
    onPeriod: (OrderPeriod) -> Unit, onLoadMore: () -> Unit, onOpen: (Long) -> Unit, onExport: () -> Unit, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable(onClick = onBack).padding(end = 14.dp), fontSize = 31.sp, color = Ink)
            Column(Modifier.weight(1f)) { Text("订单档案", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink); Text("本机保存 · 下单后自动归档", fontSize = 11.sp, color = Muted) }
            TextButton(onClick = onExport) { Text("导出 CSV", color = Leaf, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(OrderPeriod.ALL to "全部", OrderPeriod.LAST_7_DAYS to "近 7 天", OrderPeriod.LAST_30_DAYS to "近 30 天").forEach { (value, label) ->
                val selected = period == value
                FilterChip(selected = selected, onClick = { onPeriod(value) }, label = { Text(label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "stats") { OrderStatsCard(stats, period) }
            item(key = "archive-title") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("订单记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.weight(1f)); Text("${stats.orderCount} 笔", fontSize = 12.sp, color = Muted)
                }
            }
            if (orders.isEmpty() && !loading) item(key = "empty") { EmptyCard("还没有订单", "完成结算后，订单会自动保存在这里", "返回菜单", onBack) }
            items(orders, key = { "order-${it.id}" }) { order ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable { onOpen(order.id) }.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("订单 #${order.id}", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f)); Text("查看明细 ›", color = Leaf, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(5.dp)); Text(formatOrderTime(order.createdAt), color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(11.dp)); HorizontalDivider(color = Line)
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${order.itemCount} 件商品", color = Muted, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                        Text(order.totalCents.asPrice(), color = LeafDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (loading) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Leaf, modifier = Modifier.size(24.dp), strokeWidth = 2.dp) } }
            else if (hasMore && orders.isNotEmpty()) item(key = "load-more") { OutlinedButton(onClick = onLoadMore, Modifier.fillMaxWidth()) { Text("加载更多订单", color = Leaf) } }
        }
    }
}

@Composable
private fun OrderStatsCard(stats: OrderStats, period: OrderPeriod) {
    val periodName = when (period) { OrderPeriod.ALL -> "全部时间"; OrderPeriod.LAST_7_DAYS -> "近 7 天"; OrderPeriod.LAST_30_DAYS -> "近 30 天" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp)) {
        Text("${periodName}统计", fontSize = 14.sp, color = Ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatValue("订单数", "${stats.orderCount}", Modifier.weight(1f))
            StatValue("消费总额", stats.totalSpentCents.asPrice(), Modifier.weight(1.2f))
            StatValue("平均每单", stats.averageOrderCents.asPrice(), Modifier.weight(1.1f))
        }
        Spacer(Modifier.height(10.dp)); Text("共 ${stats.itemCount} 件菜品", fontSize = 11.sp, color = Muted)
        if (stats.popularDishes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp)); HorizontalDivider(color = Line); Spacer(Modifier.height(10.dp))
            Text("常点菜品", fontSize = 12.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            stats.popularDishes.forEachIndexed { index, dish ->
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", color = Leaf, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp)); Text("${dish.dishName} · ${dish.shopName}", Modifier.weight(1f), color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${dish.quantity} 份", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F7F3)).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp)); Text(value, color = LeafDark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OrderDetailDialog(detail: OrderDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订单 #${detail.summary.id}", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(formatOrderTime(detail.summary.createdAt), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = Line)
                detail.lines.forEach { line ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("${line.dishName}  × ${line.quantity}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(line.shopName + if (line.note.isBlank()) "" else " · ${line.note}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                            Text("${line.unitPriceCents.asPrice()} / 份", color = Muted, fontSize = 12.sp); Spacer(Modifier.weight(1f))
                            Text((line.unitPriceCents * line.quantity).asPrice(), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    HorizontalDivider(color = Line)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("订单合计 · ${detail.summary.itemCount} 件", color = Muted, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                    Text(detail.summary.totalCents.asPrice(), color = LeafDark, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = Leaf) } },
        containerColor = Color.White
    )
}

private fun formatOrderTime(timestamp: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

@Composable
private fun BottomBar(cartCount: Int, total: Int, selected: Boolean, onCart: () -> Unit, onOrders: () -> Unit) {
    val shownCount by animateIntAsState(cartCount, tween(180), label = "cart-count")
    val shownTotal by animateIntAsState(total, tween(180), label = "cart-total")
    Surface(color = Color.White, shadowElevation = 10.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(67.dp).padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOrders)) {
                Text("订单", color = Muted, fontSize = 11.sp); Text("查看本地记录", color = Muted, fontSize = 12.sp)
            }
            Button(onClick = onCart, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = if (selected) LeafDark else Leaf), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp)) {
                Text("🛒 购物车  $shownCount 件   ${shownTotal.asPrice()}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HeaderBar(title: String, subtitle: String, onOrders: (() -> Unit)? = null, onBackup: (() -> Unit)? = null, onAccount: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE8F3EC)), contentAlignment = Alignment.Center) { Text("🌿", fontSize = 19.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp) }
        if (onOrders != null) TextButton(onClick = onOrders) { Text("订单", color = Leaf) }
        if (onBackup != null) TextButton(onClick = onBackup) { Text("备份", color = Leaf) }
        if (onAccount != null) TextButton(onClick = onAccount) { Text("账号", color = Leaf) }
    }
}

@Composable
private fun AuthScreen(busy: Boolean, error: String?, onSubmit: (String, String, Boolean) -> Unit) {
    var register by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Canvas).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFE8F3EC)), contentAlignment = Alignment.Center) { Text("🌿", fontSize = 38.sp) }
        Text("华农食堂", color = Ink, fontWeight = FontWeight.Bold, fontSize = 27.sp, modifier = Modifier.padding(top = 18.dp))
        Text("登录后在不同设备同步菜单与订单", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 7.dp, bottom = 28.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE9EEE9)).padding(4.dp)) {
            listOf(true to "注册账号", false to "登录账号").forEach { (isRegister, label) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (register == isRegister) Color.White else Color.Transparent).clickable { register = isRegister }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (register == isRegister) LeafDark else Muted, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().padding(top = 20.dp), label = { Text("邮箱") }, singleLine = true, shape = RoundedCornerShape(14.dp), enabled = !busy)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("密码（至少 8 位）") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), enabled = !busy)
        if (!error.isNullOrBlank()) Text(error, Modifier.fillMaxWidth().padding(top = 12.dp), color = Color(0xFFB3261E), fontSize = 13.sp)
        Button(onClick = { onSubmit(email, password, register) }, enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Leaf)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(if (register) "创建账号并同步" else "登录并同步", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("密码经加密验证；店铺、菜品、订单和图片归账号保存", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun QuantityControl(quantity: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    val shownQuantity by animateIntAsState(quantity, tween(160), label = "quantity")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(30.dp), contentPadding = PaddingValues(0.dp), shape = CircleShape, border = BorderStroke(1.dp, Line)) { Text("−", color = Muted) }
        Text(shownQuantity.toString(), fontSize = 14.sp, color = Ink, minLines = 1)
        FilledIconButton(onClick = onPlus, modifier = Modifier.size(30.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Leaf)) { Text("+", color = Color.White) }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🥬", fontSize = 35.sp); Spacer(Modifier.height(9.dp)); Text(title, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp)); Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Leaf), shape = RoundedCornerShape(12.dp)) { Text(action) }
    }
}

@Composable
private fun MenuImage(path: String?, emoji: String, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) { bitmap = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) { runCatching { decodeSampledBitmap(path, 384) }.getOrNull() } }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFFE4EEE7), Color(0xFFF8EBDD)))), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text(emoji, fontSize = 30.sp)
    }
}

@Composable
private fun ImagePreviewDialog(title: String, path: String?, emoji: String, onDismiss: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var pan by remember(path) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(path) { bitmap = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) { runCatching { decodeSampledBitmap(path, 2048) }.getOrNull() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111511))) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("×", Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 29.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(
                    bitmap!!.asImageBitmap(), contentDescription = title,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)
                        .pointerInput(path) {
                            detectTransformGestures { _, gesturePan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                pan = if (nextScale <= 1f) Offset.Zero else pan + gesturePan
                            }
                        },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                ) else Text(emoji, fontSize = 72.sp)
            }
            Text("双指缩放 · 拖动查看", Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(14.dp), color = Color(0xFFCBD2CB), fontSize = 12.sp)
        }
    }
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (largest / (sample * 2) >= maxDimension) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun ShopEditorDialog(hallName: String, imagePath: String?, onPickImage: () -> Unit, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("在${hallName}添加店铺") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            TextField(name, { name = it }, label = { Text("店铺名称") }, singleLine = true)
            TextField(desc, { desc = it }, label = { Text("简介（可选）") }, minLines = 2)
            OutlinedButton(onClick = onPickImage) { Text(if (imagePath == null) "选择店铺图片" else "已选好店铺图片") }
        }
    }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), desc.trim()) }, enabled = name.isNotBlank()) { Text("保存", color = Leaf) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, containerColor = Color.White)
}

@Composable
private fun DishEditorDialog(imagePath: String?, onPickImage: () -> Unit, onDismiss: () -> Unit, onSave: (String, String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("推荐") }; var desc by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加菜品") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TextField(name, { name = it }, label = { Text("菜品名称") }, singleLine = true)
            TextField(category, { category = it }, label = { Text("分类") }, singleLine = true)
            TextField(desc, { desc = it }, label = { Text("介绍（可选）") }, minLines = 2)
            TextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("价格（元）") }, singleLine = true)
            OutlinedButton(onClick = onPickImage) { Text(if (imagePath == null) "选择菜品图片" else "已选好菜品图片") }
        }
    }, confirmButton = { TextButton(onClick = {
        val cents = ((price.toDoubleOrNull() ?: 0.0) * 100).toInt()
        if (name.isNotBlank() && cents > 0) onSave(name.trim(), category.trim().ifBlank { "推荐" }, desc.trim(), cents)
    }, enabled = name.isNotBlank() && (price.toDoubleOrNull() ?: 0.0) > 0) { Text("保存", color = Leaf) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, containerColor = Color.White)
}
