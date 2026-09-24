package cn.huanong.canteen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.huanong.canteen.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

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
    val loading: Boolean = true
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
    private val _state = MutableStateFlow(MenuUiState())
    val state: StateFlow<MenuUiState> = _state.asStateFlow()

    init { refresh() }

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
        withContext(Dispatchers.IO) { repository.changeCartQuantity(line, delta) }
        refresh()
    }

    fun placeOrder() = viewModelScope.launch {
        if (_state.value.placingOrder) return@launch
        _state.value = _state.value.copy(placingOrder = true)
        try {
            val id = withContext(Dispatchers.IO) { repository.placeOrder() }
            refresh()
            if (id > 0) {
                _state.value = _state.value.copy(lastOrderId = id, screen = Screen.ORDERS)
                loadOrders(reset = true)
            }
        } finally {
            _state.value = _state.value.copy(placingOrder = false)
        }
    }

    suspend fun exportBackup(output: OutputStream) = withContext(Dispatchers.IO) { output.use { repository.exportBackup(it) } }

    suspend fun exportOrdersCsv(output: OutputStream) = withContext(Dispatchers.IO) { output.use { repository.exportOrdersCsv(it) } }

    suspend fun importBackup(input: InputStream) {
        val data = withContext(Dispatchers.IO) { input.use { repository.importBackup(it) }; repository.load() }
        _state.value = _state.value.copy(snapshot = data, selectedHallId = data.halls.firstOrNull()?.id ?: 1, selectedShopId = null, screen = Screen.MENU, loading = false)
    }

    private fun mutate(block: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { block() }
        refresh()
    }
}
