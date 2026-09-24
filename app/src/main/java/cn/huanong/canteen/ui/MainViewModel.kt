package cn.huanong.canteen.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.huanong.canteen.data.*
import cn.huanong.canteen.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.File

data class MenuUiState(
    val snapshot: AppSnapshot = AppSnapshot(),
    val selectedHallId: Long = 1,
    val selectedShopId: Long? = null,
    val screen: Screen = Screen.MENU,
    val lastOrderId: Long? = null,
    val orders: List<OrderSummary> = emptyList(),
    val orderStats: OrderStats = OrderStats(),
    val orderPeriod: OrderPeriod = OrderPeriod.ALL,
    val ordersHasMore: Boolean = true,
    val ordersLoading: Boolean = false,
    val orderDetail: OrderDetail? = null,
    val detailLoading: Boolean = false,
    val placingOrder: Boolean = false,
    val loading: Boolean = true,
    val account: CloudAccount? = null,
    val authBusy: Boolean = false,
    val authError: String? = null,
    val cloudStatus: String = "仅保存在本机",
    val offlineMode: Boolean = false,
    val syncConflict: Boolean = false
)
enum class Screen { MENU, CART, ORDERS }
enum class OrderPeriod {
    ALL, LAST_7_DAYS, LAST_30_DAYS;

    fun startAt(): Long? {
        if (this == ALL) return null
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        calendar.add(java.util.Calendar.DAY_OF_YEAR, if (this == LAST_7_DAYS) -6 else -29)
        return calendar.timeInMillis
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: MenuRepository = SqliteMenuRepository(application)
    private val sessions = SessionStore(application)
    private val offlineSync = OfflineSyncStore(application)
    private val cloud = CloudApi(BuildConfig.CLOUD_API_BASE, sessions, BuildConfig.SITE_GATE_TOKEN)
    private val syncMutex = Mutex()
    private val localWriteMutex = Mutex()
    private var cloudRevision = 0
    private val _state = MutableStateFlow(MenuUiState())
    val state: StateFlow<MenuUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { repository.load() }
            val account = if (sessions.token() != null) sessions.account() else null
            _state.value = _state.value.copy(
                snapshot = local,
                loading = false,
                account = account,
                offlineMode = offlineSync.offlineMode(),
                cloudStatus = if (account != null) "正在连接" else if (offlineSync.offlineMode()) "离线使用 · 本机保存" else "仅保存在本机"
            )
            if (account != null) {
                if (hasInternetConnection()) syncAccount()
                else showOfflineStatus(account)
            }
        }
    }

    private fun hasInternetConnection(): Boolean = runCatching {
        val manager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun showOfflineStatus(account: CloudAccount) {
        val pending = offlineSync.hasPending(account.id)
        _state.value = _state.value.copy(
            account = account,
            loading = false,
            cloudStatus = if (pending) "离线使用 · 修改待同步" else "离线使用 · 本机数据可用",
            authError = null
        )
    }

    private fun handleSyncFailure(error: Exception, account: CloudAccount) {
        if (error is ApiHttpException && error.statusCode == 401) {
            sessions.clear()
            _state.value = _state.value.copy(
                account = null,
                offlineMode = true,
                authError = "登录已过期；本机菜单和订单仍可离线使用，重新登录后可继续同步",
                cloudStatus = "离线使用 · 本机数据保留"
            )
            offlineSync.setOfflineMode(true)
            return
        }
        val conflict = error is ApiHttpException && error.statusCode == 409
        val pending = offlineSync.hasPending(account.id)
        _state.value = _state.value.copy(
            account = account,
            offlineMode = false,
            syncConflict = _state.value.syncConflict || conflict,
            cloudStatus = when {
                conflict -> "同步冲突 · 本机数据已保留"
                pending -> "离线使用 · 修改待同步"
                else -> "离线使用 · 本机数据可用"
            },
            authError = if (conflict) "云端内容在其他设备有更新。请选择保留本机数据或使用云端数据。" else error.message
        )
    }

    fun authenticate(email: String, password: String, register: Boolean) = viewModelScope.launch {
        if (_state.value.authBusy) return@launch
        _state.value = _state.value.copy(authBusy = true, authError = null, cloudStatus = "正在连接")
        try {
            val session = withContext(Dispatchers.IO) {
                if (register) cloud.register(email.trim(), password) else cloud.login(email.trim(), password)
            }
            sessions.save(session)
            offlineSync.setOfflineMode(false)
            if (offlineSync.hasGuestPending()) offlineSync.markPending(session.account.id)
            _state.value = _state.value.copy(
                account = session.account,
                authBusy = false,
                authError = null,
                offlineMode = false,
                cloudStatus = "正在同步"
            )
            syncAccount()
        } catch (e: Exception) {
            _state.value = _state.value.copy(authBusy = false, authError = e.message ?: "操作失败", cloudStatus = "连接失败")
        }
    }

    fun continueOffline() {
        offlineSync.setOfflineMode(true)
        _state.value = _state.value.copy(
            offlineMode = true,
            authError = null,
            cloudStatus = "离线使用 · 本机保存"
        )
    }

    fun showCloudLogin() {
        offlineSync.setOfflineMode(false)
        _state.value = _state.value.copy(offlineMode = false, authError = null)
    }

    fun syncNow() = viewModelScope.launch { syncAccount() }

    fun onNetworkAvailable() {
        if (_state.value.account != null) viewModelScope.launch { syncAccount() }
    }

    fun onNetworkUnavailable() {
        val account = _state.value.account ?: return
        showOfflineStatus(account)
    }

    private suspend fun syncAccount() {
        val account = _state.value.account ?: sessions.account() ?: return
        if (sessions.token() == null) {
            showOfflineStatus(account)
            return
        }
        if (!hasInternetConnection()) {
            showOfflineStatus(account)
            return
        }
        syncMutex.withLock {
            try {
                val verified = withContext(Dispatchers.IO) { cloud.checkSession() }
                _state.value = _state.value.copy(account = verified, authError = null, syncConflict = false)
                if (offlineSync.hasPending(verified.id)) {
                    val remote = withContext(Dispatchers.IO) { cloud.getState() }
                    val baseRevision = offlineSync.lastRevision(verified.id)
                    if (remote.revision != baseRevision) {
                        _state.value = _state.value.copy(
                            syncConflict = true,
                            cloudStatus = "同步冲突 · 本机数据已保留",
                            authError = "云端内容在其他设备有更新。请选择保留本机数据或使用云端数据。"
                        )
                    } else {
                        cloudRevision = baseRevision
                        syncLocalToCloudLocked(verified.id)
                    }
                } else {
                    syncFromCloudLocked(verified.id)
                }
            } catch (e: Exception) {
                handleSyncFailure(e, account)
            }
        }
    }

    private suspend fun syncFromCloudLocked(accountId: String) {
        val remote = withContext(Dispatchers.IO) { cloud.getState() }
        cloudRevision = remote.revision
        if (remote.payload != null) {
            val generation = offlineSync.generation(accountId)
            val replaced = localWriteMutex.withLock {
                if (offlineSync.hasPending(accountId) || offlineSync.generation(accountId) != generation) false
                else {
                    withContext(Dispatchers.IO) {
                        repository.replaceCloudSnapshot(remote.payload)
                        downloadCloudImages()
                    }
                    true
                }
            }
            if (!replaced) {
                if (remote.revision != offlineSync.lastRevision(accountId)) {
                    _state.value = _state.value.copy(
                        syncConflict = true,
                        cloudStatus = "同步冲突 · 本机数据已保留",
                        authError = "云端内容在其他设备有更新。请选择保留本机数据或使用云端数据。"
                    )
                    return
                }
                syncLocalToCloudLocked(accountId)
                return
            }
            val clean = offlineSync.markSyncedIfUnchanged(accountId, generation, remote.revision)
            if (!clean) {
                syncLocalToCloudLocked(accountId)
                return
            }
            _state.value = _state.value.copy(
                snapshot = withContext(Dispatchers.IO) { repository.load() },
                cloudStatus = "已同步",
                authError = null,
                syncConflict = false
            )
        } else {
            val generation = offlineSync.generation(accountId)
            val payload = withContext(Dispatchers.IO) {
                uploadLocalImages()
                repository.exportCloudSnapshot()
            }
            val revision = withContext(Dispatchers.IO) { cloud.putState(remote.revision, payload) }
            cloudRevision = revision
            offlineSync.markSyncedIfUnchanged(accountId, generation, revision)
            offlineSync.clearGuestPending()
            _state.value = _state.value.copy(
                snapshot = withContext(Dispatchers.IO) { repository.load() },
                cloudStatus = if (offlineSync.hasPending(accountId)) "有新修改待同步" else "已同步",
                authError = null,
                syncConflict = false
            )
        }
    }

    private suspend fun syncLocalToCloud() {
        val account = _state.value.account ?: return
        if (!offlineSync.hasPending(account.id)) return
        if (!hasInternetConnection()) {
            showOfflineStatus(account)
            return
        }
        syncMutex.withLock {
            try {
                syncLocalToCloudLocked(account.id)
            } catch (e: Exception) {
                handleSyncFailure(e, account)
            }
        }
    }

    private suspend fun syncLocalToCloudLocked(accountId: String) {
        if (!offlineSync.hasPending(accountId)) return
        val generation = offlineSync.generation(accountId)
        val payload = withContext(Dispatchers.IO) {
            uploadLocalImages()
            repository.exportCloudSnapshot()
        }
        val revision = withContext(Dispatchers.IO) {
            cloud.putState(offlineSync.lastRevision(accountId), payload)
        }
        cloudRevision = revision
        val clean = offlineSync.markSyncedIfUnchanged(accountId, generation, revision)
        if (clean) offlineSync.clearGuestPending()
        _state.value = _state.value.copy(
            snapshot = withContext(Dispatchers.IO) { repository.load() },
            cloudStatus = if (clean) "已同步" else "有新修改待同步",
            authError = null,
            syncConflict = false
        )
    }

    fun resolveConflictKeepLocal() = viewModelScope.launch {
        val account = _state.value.account ?: return@launch
        if (!hasInternetConnection()) return@launch
        syncMutex.withLock {
            try {
                withContext(Dispatchers.IO) { cloud.checkSession() }
                val remote = withContext(Dispatchers.IO) { cloud.getState() }
                offlineSync.setRevision(account.id, remote.revision)
                if (!offlineSync.hasPending(account.id)) offlineSync.markPending(account.id)
                syncLocalToCloudLocked(account.id)
            } catch (e: Exception) {
                handleSyncFailure(e, account)
            }
        }
    }

    fun resolveConflictUseCloud() = viewModelScope.launch {
        val account = _state.value.account ?: return@launch
        if (!hasInternetConnection()) return@launch
        syncMutex.withLock {
            try {
                withContext(Dispatchers.IO) { cloud.checkSession() }
                val remote = withContext(Dispatchers.IO) { cloud.getState() }
                localWriteMutex.withLock {
                    withContext(Dispatchers.IO) {
                        if (remote.payload != null) {
                            repository.replaceCloudSnapshot(remote.payload)
                            downloadCloudImages()
                        } else {
                            repository.resetForLogout()
                        }
                    }
                }
                offlineSync.acceptRemote(account.id, remote.revision)
                offlineSync.clearGuestPending()
                _state.value = _state.value.copy(
                    snapshot = withContext(Dispatchers.IO) { repository.load() },
                    cloudStatus = "已同步",
                    authError = null,
                    syncConflict = false
                )
            } catch (e: Exception) {
                handleSyncFailure(e, account)
            }
        }
    }

    private fun uploadLocalImages() {
        repository.pendingCloudImages().forEach { image ->
            val file = File(image.path)
            if (file.isFile && file.length() <= 5L * 1024 * 1024) {
                val imageId = cloud.uploadImageRaw(file.readBytes())
                repository.setCloudImageId(image.entity, image.entityId, imageId)
            }
        }
    }

    private fun downloadCloudImages() {
        repository.cloudImagesNeedingDownload().forEach { image ->
            runCatching {
                val bytes = cloud.downloadImage(image.path)
                val folder = File(getApplication<Application>().filesDir, "menu-images").apply { mkdirs() }
                val file = File(folder, "${image.path}.img")
                file.writeBytes(bytes)
                repository.setImagePath(image.entity, image.entityId, file.absolutePath)
            }
        }
    }

    fun logout() = viewModelScope.launch {
        val account = _state.value.account
        if (account != null && offlineSync.hasPending(account.id)) {
            syncAccount()
            if (offlineSync.hasPending(account.id)) {
                _state.value = _state.value.copy(
                    authError = "还有未同步的本机修改。恢复网络并完成同步后再退出，避免丢失数据。"
                )
                return@launch
            }
        }
        _state.value = _state.value.copy(cloudStatus = "正在退出")
        withContext(Dispatchers.IO) {
            runCatching { cloud.logout() }
            sessions.clear()
            repository.resetForLogout()
        }
        offlineSync.setOfflineMode(false)
        offlineSync.clearGuestPending()
        cloudRevision = 0
        val data = withContext(Dispatchers.IO) { repository.load() }
        _state.value = MenuUiState(snapshot = data, loading = false, cloudStatus = "仅保存在本机")
    }

    fun refresh() = viewModelScope.launch {
        val data = withContext(Dispatchers.IO) { repository.load() }
        _state.value = _state.value.copy(snapshot = data, loading = false)
    }

    fun selectHall(id: Long) { _state.value = _state.value.copy(selectedHallId = id, selectedShopId = null, screen = Screen.MENU) }
    fun openShop(id: Long) { _state.value = _state.value.copy(selectedShopId = id, screen = Screen.MENU) }
    fun backToHalls() { _state.value = _state.value.copy(selectedShopId = null, screen = Screen.MENU) }
    fun show(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
        if (screen == Screen.ORDERS) loadOrders(reset = true)
    }

    fun loadMoreOrders() = loadOrders(reset = false)

    fun setOrderPeriod(period: OrderPeriod) {
        if (period == _state.value.orderPeriod) return
        loadOrders(reset = true, period = period)
    }

    private fun loadOrders(reset: Boolean, period: OrderPeriod = _state.value.orderPeriod) = viewModelScope.launch {
        val offset = if (reset) 0 else _state.value.orders.size
        if (!reset && (_state.value.ordersLoading || !_state.value.ordersHasMore)) return@launch
        _state.value = _state.value.copy(
            orderPeriod = period,
            orders = if (reset) emptyList() else _state.value.orders,
            ordersLoading = true
        )
        val startAt = period.startAt()
        val result = withContext(Dispatchers.IO) {
            repository.loadOrders(offset, 50, startAt) to if (reset) repository.getOrderStats(startAt) else _state.value.orderStats
        }
        _state.value = _state.value.copy(
            orders = if (reset) result.first else _state.value.orders + result.first,
            orderStats = result.second,
            ordersHasMore = result.first.size == 50,
            ordersLoading = false
        )
    }

    fun openOrder(orderId: Long) = viewModelScope.launch {
        _state.value = _state.value.copy(orderDetail = null, detailLoading = true)
        val detail = withContext(Dispatchers.IO) { repository.getOrderDetail(orderId) }
        _state.value = _state.value.copy(orderDetail = detail, detailLoading = false)
    }

    fun closeOrderDetail() { _state.value = _state.value.copy(orderDetail = null, detailLoading = false) }

    fun addShop(hallId: Long, name: String, desc: String, imagePath: String?) = mutate { repository.addShop(hallId, name, desc, imagePath) }
    fun deleteShop(shopId: Long) = mutate { repository.deleteShop(shopId) }
    fun addDish(shopId: Long, name: String, category: String, desc: String, priceCents: Int, imagePath: String?) =
        mutate { repository.addDish(shopId, name, category, desc, priceCents, imagePath) }

    fun adjust(line: CartLine, delta: Int) = viewModelScope.launch {
        markLocalChange()
        localWriteMutex.withLock { withContext(Dispatchers.IO) { repository.changeCartQuantity(line, delta) } }
        refresh()
        syncLocalToCloud()
    }

    fun placeOrder() = viewModelScope.launch {
        if (_state.value.placingOrder) return@launch
        _state.value = _state.value.copy(placingOrder = true)
        try {
            markLocalChange()
            val id = localWriteMutex.withLock { withContext(Dispatchers.IO) { repository.placeOrder() } }
            refresh()
            if (id > 0) {
                _state.value = _state.value.copy(lastOrderId = id, screen = Screen.ORDERS)
                loadOrders(reset = true)
            }
            syncLocalToCloud()
        } finally {
            _state.value = _state.value.copy(placingOrder = false)
        }
    }

    suspend fun exportBackup(output: OutputStream) = withContext(Dispatchers.IO) { output.use { repository.exportBackup(it) } }

    suspend fun exportOrdersCsv(output: OutputStream) = withContext(Dispatchers.IO) { output.use { repository.exportOrdersCsv(it) } }

    suspend fun importBackup(input: InputStream) {
        markLocalChange()
        val data = localWriteMutex.withLock {
            withContext(Dispatchers.IO) { input.use { repository.importBackup(it) }; repository.load() }
        }
        _state.value = _state.value.copy(snapshot = data, selectedHallId = data.halls.firstOrNull()?.id ?: 1, selectedShopId = null, screen = Screen.MENU, loading = false)
        syncLocalToCloud()
    }

    private suspend fun markLocalChange() {
        val accountId = _state.value.account?.id
        if (accountId != null) offlineSync.markPending(accountId)
        else offlineSync.markGuestPending()
    }

    private fun mutate(block: () -> Unit) = viewModelScope.launch {
        markLocalChange()
        localWriteMutex.withLock { withContext(Dispatchers.IO) { block() } }
        refresh()
        syncLocalToCloud()
    }
}

