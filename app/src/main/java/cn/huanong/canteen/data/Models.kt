package cn.huanong.canteen.data

data class Hall(val id: Long, val name: String)
data class Shop(
    val id: Long,
    val hallId: Long,
    val name: String,
    val description: String = "",
    val imagePath: String? = null,
    val cloudImageId: String? = null
)
data class Dish(
    val id: Long,
    val shopId: Long,
    val name: String,
    val category: String,
    val description: String,
    val priceCents: Int,
    val imagePath: String? = null,
    val cloudImageId: String? = null
)
data class CloudImageUpload(val entity: String, val entityId: Long, val path: String)
data class CartLine(
    val dishId: Long,
    val shopId: Long,
    val shopName: String,
    val dishName: String,
    val unitPriceCents: Int,
    val quantity: Int,
    val note: String = ""
)
data class OrderSummary(
    val id: Long,
    val createdAt: Long,
    val totalCents: Int,
    val itemCount: Int
)

data class OrderLine(
    val shopName: String,
    val dishName: String,
    val unitPriceCents: Int,
    val quantity: Int,
    val note: String
)

data class OrderDetail(val summary: OrderSummary, val lines: List<OrderLine>)
data class PopularDish(val shopName: String, val dishName: String, val quantity: Int)
data class OrderStats(
    val orderCount: Int = 0,
    val totalSpentCents: Int = 0,
    val averageOrderCents: Int = 0,
    val itemCount: Int = 0,
    val popularDishes: List<PopularDish> = emptyList()
)

data class AppSnapshot(
    val halls: List<Hall> = emptyList(),
    val shops: List<Shop> = emptyList(),
    val dishes: List<Dish> = emptyList(),
    val cart: List<CartLine> = emptyList(),
    val orders: List<OrderSummary> = emptyList()
)

fun Int.asPrice(): String = java.lang.String.format(java.util.Locale.US, "¥%.2f", this / 100.0)
