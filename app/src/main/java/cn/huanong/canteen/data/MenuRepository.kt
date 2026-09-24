package cn.huanong.canteen.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Database access is kept behind this repository so a remote implementation can replace it later. */
interface MenuRepository {
    fun load(): AppSnapshot
    fun addShop(hallId: Long, name: String, description: String, imagePath: String?)
    fun deleteShop(shopId: Long)
    fun addDish(shopId: Long, name: String, category: String, description: String, priceCents: Int, imagePath: String?)
    fun changeCartQuantity(line: CartLine, delta: Int)
    fun placeOrder(): Long
    fun loadOrders(offset: Int, limit: Int, startAt: Long? = null): List<OrderSummary>
    fun getOrderDetail(orderId: Long): OrderDetail?
    fun getOrderStats(startAt: Long? = null): OrderStats
    fun exportOrdersCsv(output: OutputStream)
    fun exportBackup(output: OutputStream)
    fun importBackup(input: InputStream)
    fun exportCloudSnapshot(): String
    fun replaceCloudSnapshot(json: String)
    fun pendingCloudImages(): List<CloudImageUpload>
    fun cloudImagesNeedingDownload(): List<CloudImageUpload>
    fun setCloudImageId(entity: String, id: Long, imageId: String)
    fun imagePath(entity: String, id: Long): String?
    fun setImagePath(entity: String, id: Long, path: String)
    fun resetForLogout()
}

class SqliteMenuRepository(context: Context) : MenuRepository {
    private val helper = MenuDb(context.applicationContext)

    private data class PendingLine(
        val shopId: Long,
        val shopName: String,
        val dishId: Long,
        val dishName: String,
        val unitPrice: Int,
        val quantity: Int,
        val note: String
    )

    override fun load(): AppSnapshot {
        val db = helper.readableDatabase
        val halls = db.rawQuery("SELECT id,name FROM halls ORDER BY sort_order", null).use { c ->
            buildList { while (c.moveToNext()) add(Hall(c.getLong(0), c.getString(1))) }
        }
        val shops = db.rawQuery("SELECT id,hall_id,name,description,image_path,cloud_image_id FROM shops ORDER BY id DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(Shop(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getStringOrNull(4), c.getStringOrNull(5)))
            }
        }
        val dishes = db.rawQuery("SELECT id,shop_id,name,category,description,price_cents,image_path,cloud_image_id FROM dishes ORDER BY id DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(Dish(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getStringOrNull(6), c.getStringOrNull(7)))
            }
        }
        val cart = db.rawQuery("SELECT dish_id,shop_id,shop_name,dish_name,unit_price,quantity,note FROM cart", null).use { c ->
            buildList {
                while (c.moveToNext()) add(CartLine(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getString(6)))
            }
        }
        val orders = db.rawQuery("SELECT id,created_at,total_cents,item_count FROM orders ORDER BY id DESC LIMIT 20", null).use { c ->
            buildList { while (c.moveToNext()) add(OrderSummary(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3))) }
        }
        return AppSnapshot(halls, shops, dishes, cart, orders)
    }

    override fun addShop(hallId: Long, name: String, description: String, imagePath: String?) {
        val v = ContentValues().apply {
            put("hall_id", hallId); put("name", name); put("description", description); put("image_path", imagePath)
        }
        helper.writableDatabase.insertOrThrow("shops", null, v)
    }

    override fun deleteShop(shopId: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("cart", "shop_id=?", arrayOf(shopId.toString()))
            db.delete("shops", "id=?", arrayOf(shopId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun addDish(shopId: Long, name: String, category: String, description: String, priceCents: Int, imagePath: String?) {
        val v = ContentValues().apply {
            put("shop_id", shopId); put("name", name); put("category", category); put("description", description)
            put("price_cents", priceCents); put("image_path", imagePath)
        }
        helper.writableDatabase.insertOrThrow("dishes", null, v)
    }

    override fun changeCartQuantity(line: CartLine, delta: Int) {
        val db = helper.writableDatabase
        val key = "${line.dishId}:${line.note}"
        db.beginTransaction()
        try {
            val current = db.rawQuery("SELECT quantity FROM cart WHERE cart_key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val quantity = current + delta
            if (quantity <= 0) {
                db.delete("cart", "cart_key=?", arrayOf(key))
            } else {
                val v = ContentValues().apply {
                    put("cart_key", key); put("dish_id", line.dishId); put("shop_id", line.shopId)
                    put("shop_name", line.shopName); put("dish_name", line.dishName); put("unit_price", line.unitPriceCents)
                    put("quantity", quantity); put("note", line.note)
                }
                db.insertWithOnConflict("cart", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun placeOrder(): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val lines = db.rawQuery("SELECT shop_id,shop_name,dish_id,dish_name,unit_price,quantity,note FROM cart", null).use { c ->
                buildList<PendingLine> {
                    while (c.moveToNext()) add(PendingLine(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4), c.getInt(5), c.getString(6)))
                }
            }
            if (lines.isEmpty()) return -1L
            val total = lines.sumOf { it.unitPrice * it.quantity }
            val count = lines.sumOf { it.quantity }
            val orderId = db.insertOrThrow("orders", null, ContentValues().apply {
                put("created_at", System.currentTimeMillis()); put("total_cents", total); put("item_count", count)
            })
            lines.forEach { a ->
                db.insertOrThrow("order_lines", null, ContentValues().apply {
                    put("order_id", orderId); put("shop_id", a.shopId); put("shop_name", a.shopName)
                    put("dish_id", a.dishId); put("dish_name", a.dishName); put("unit_price", a.unitPrice)
                    put("quantity", a.quantity); put("note", a.note)
                })
            }
            db.delete("cart", null, null)
            db.setTransactionSuccessful()
            return orderId
        } finally { db.endTransaction() }
    }

    override fun loadOrders(offset: Int, limit: Int, startAt: Long?): List<OrderSummary> {
        val where = if (startAt == null) "" else "WHERE created_at >= ?"
        val args = if (startAt == null) arrayOf(offset.toString(), limit.toString()) else arrayOf(startAt.toString(), offset.toString(), limit.toString())
        return helper.readableDatabase.rawQuery(
            "SELECT id,created_at,total_cents,item_count FROM orders $where ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", args
        ).use { c -> buildList { while (c.moveToNext()) add(OrderSummary(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3))) } }
    }

    override fun getOrderDetail(orderId: Long): OrderDetail? {
        val db = helper.readableDatabase
        val summary = db.rawQuery("SELECT id,created_at,total_cents,item_count FROM orders WHERE id=?", arrayOf(orderId.toString())).use { c ->
            if (c.moveToFirst()) OrderSummary(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3)) else null
        } ?: return null
        val lines = db.rawQuery("SELECT shop_name,dish_name,unit_price,quantity,note FROM order_lines WHERE order_id=? ORDER BY id", arrayOf(orderId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(OrderLine(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4))) }
        }
        return OrderDetail(summary, lines)
    }

    override fun getOrderStats(startAt: Long?): OrderStats {
        val db = helper.readableDatabase
        val where = if (startAt == null) "" else "WHERE created_at >= ?"
        val args = if (startAt == null) null else arrayOf(startAt.toString())
        val base = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(total_cents),0),COALESCE(AVG(total_cents),0),COALESCE(SUM(item_count),0) FROM orders $where", args).use { c ->
            if (c.moveToFirst()) OrderStats(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3)) else OrderStats()
        }
        val lineWhere = if (startAt == null) "" else "WHERE o.created_at >= ?"
        val popular = db.rawQuery(
            "SELECT l.shop_name,l.dish_name,SUM(l.quantity) AS qty FROM order_lines l INNER JOIN orders o ON o.id=l.order_id $lineWhere GROUP BY l.shop_name,l.dish_name ORDER BY qty DESC,l.dish_name LIMIT 5", args
        ).use { c -> buildList { while (c.moveToNext()) add(PopularDish(c.getString(0), c.getString(1), c.getInt(2))) } }
        return base.copy(popularDishes = popular)
    }

    override fun exportOrdersCsv(output: OutputStream) {
        val db = helper.readableDatabase
        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF订单编号,下单时间,店铺,菜品,口味备注,数量,单价,菜品小计,订单总额\r\n")
            db.rawQuery(
                "SELECT o.id,o.created_at,l.shop_name,l.dish_name,l.note,l.quantity,l.unit_price,o.total_cents FROM orders o INNER JOIN order_lines l ON l.order_id=o.id ORDER BY o.created_at DESC,o.id DESC,l.id", null
            ).use { c ->
                while (c.moveToNext()) {
                    val values = listOf(
                        c.getLong(0).toString(),
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(c.getLong(1))),
                        c.getString(2), c.getString(3), c.getString(4), c.getInt(5).toString(),
                        "%.2f".format(java.util.Locale.US, c.getInt(6) / 100.0),
                        "%.2f".format(java.util.Locale.US, c.getInt(6) * c.getInt(5) / 100.0),
                        "%.2f".format(java.util.Locale.US, c.getInt(7) / 100.0)
                    )
                    writer.write(values.joinToString(",") { csv(it) })
                    writer.write("\r\n")
                }
            }
            writer.flush()
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    override fun exportBackup(output: OutputStream) {
        val db = helper.writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
        helper.close()
        val dbFile = helperContext.getDatabasePath("huanong_canteen.db")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("huanong_canteen.db")); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            val images = File(helperContext.filesDir, "menu-images")
            if (images.exists()) images.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry("menu-images/${file.relativeTo(images).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    override fun importBackup(input: InputStream) {
        val root = File(helperContext.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "huanong_canteen.db") {
                        File(root, name).outputStream().use { zip.copyTo(it) }
                    } else if (name.startsWith("menu-images/") && !entry.isDirectory) {
                        val relative = name.removePrefix("menu-images/")
                        require(relative.isNotBlank() && relative.split('/').none { it == ".." || it.isBlank() }) { "备份文件路径无效" }
                        val target = File(root, "menu-images/$relative")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            val stagedDb = File(root, "huanong_canteen.db")
            require(stagedDb.isFile && stagedDb.length() > 0) { "备份中没有数据库" }
            SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val expectedTables = setOf("halls", "shops", "dishes", "cart", "orders", "order_lines")
                val foundTables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
                require(foundTables.containsAll(expectedTables)) { "备份数据格式不正确" }
            }
            helper.close()
            val liveDb = helperContext.getDatabasePath("huanong_canteen.db")
            File("${liveDb.absolutePath}-wal").delete()
            File("${liveDb.absolutePath}-shm").delete()
            val liveImages = File(helperContext.filesDir, "menu-images")
            val previousDb = File(root, "previous.db")
            val previousImages = File(root, "previous-images")
            try {
                if (liveDb.exists()) check(liveDb.renameTo(previousDb)) { "无法暂存现有数据" }
                if (liveImages.exists()) check(liveImages.renameTo(previousImages)) { "无法暂存现有图片" }
                check(stagedDb.renameTo(liveDb)) { "无法安装备份数据库" }
                val stagedImages = File(root, "menu-images")
                if (stagedImages.exists()) check(stagedImages.renameTo(liveImages)) { "无法恢复菜单图片" }
                previousDb.delete(); previousImages.deleteRecursively()
            } catch (e: Exception) {
                liveDb.delete(); liveImages.deleteRecursively()
                if (previousDb.exists()) previousDb.renameTo(liveDb)
                if (previousImages.exists()) previousImages.renameTo(liveImages)
                throw e
            }
        } finally { root.deleteRecursively() }
    }

    override fun exportCloudSnapshot(): String {
        val db = helper.readableDatabase
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("halls", JSONArray().also { a ->
            db.rawQuery("SELECT id,name,sort_order FROM halls ORDER BY sort_order", null).use { c ->
                while (c.moveToNext()) a.put(JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("sort", c.getInt(2)))
            }
        })
        root.put("shops", JSONArray().also { a ->
            db.rawQuery("SELECT id,hall_id,name,description,cloud_image_id FROM shops ORDER BY id", null).use { c ->
                while (c.moveToNext()) a.put(JSONObject().put("id", c.getLong(0)).put("hallId", c.getLong(1)).put("name", c.getString(2)).put("description", c.getString(3)).put("imageId", c.getStringOrNull(4)))
            }
        })
        root.put("dishes", JSONArray().also { a ->
            db.rawQuery("SELECT id,shop_id,name,category,description,price_cents,cloud_image_id FROM dishes ORDER BY id", null).use { c ->
                while (c.moveToNext()) a.put(JSONObject().put("id", c.getLong(0)).put("shopId", c.getLong(1)).put("name", c.getString(2)).put("category", c.getString(3)).put("description", c.getString(4)).put("price", c.getInt(5)).put("imageId", c.getStringOrNull(6)))
            }
        })
        root.put("cart", JSONArray().also { a ->
            db.rawQuery("SELECT dish_id,shop_id,shop_name,dish_name,unit_price,quantity,note FROM cart ORDER BY cart_key", null).use { c ->
                while (c.moveToNext()) a.put(JSONObject().put("dishId", c.getLong(0)).put("shopId", c.getLong(1)).put("shopName", c.getString(2)).put("dishName", c.getString(3)).put("price", c.getInt(4)).put("quantity", c.getInt(5)).put("note", c.getString(6)))
            }
        })
        root.put("orders", JSONArray().also { a ->
            db.rawQuery("SELECT id,created_at,total_cents,item_count FROM orders ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    val order = JSONObject().put("id", c.getLong(0)).put("createdAt", c.getLong(1)).put("total", c.getInt(2)).put("count", c.getInt(3))
                    val lines = JSONArray()
                    db.rawQuery("SELECT shop_id,shop_name,dish_id,dish_name,unit_price,quantity,note FROM order_lines WHERE order_id=? ORDER BY id", arrayOf(c.getLong(0).toString())).use { l ->
                        while (l.moveToNext()) lines.put(JSONObject().put("shopId", l.getLong(0)).put("shopName", l.getString(1)).put("dishId", l.getLong(2)).put("dishName", l.getString(3)).put("price", l.getInt(4)).put("quantity", l.getInt(5)).put("note", l.getString(6)))
                    }
                    order.put("lines", lines); a.put(order)
                }
            }
        })
        return root.toString()
    }

    override fun replaceCloudSnapshot(json: String) {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion") == 1) { "云端数据版本不兼容" }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            listOf("order_lines", "orders", "cart", "dishes", "shops", "halls").forEach { db.delete(it, null, null) }
            val halls = root.optJSONArray("halls") ?: JSONArray()
            for (i in 0 until halls.length()) { val x = halls.getJSONObject(i); db.insertOrThrow("halls", null, ContentValues().apply { put("id", x.getLong("id")); put("name", x.getString("name")); put("sort_order", x.optInt("sort", i)) }) }
            val shops = root.optJSONArray("shops") ?: JSONArray()
            for (i in 0 until shops.length()) { val x = shops.getJSONObject(i); db.insertOrThrow("shops", null, ContentValues().apply { put("id", x.getLong("id")); put("hall_id", x.getLong("hallId")); put("name", x.getString("name")); put("description", x.optString("description")); put("cloud_image_id", x.optString("imageId").ifBlank { null }) }) }
            val dishes = root.optJSONArray("dishes") ?: JSONArray()
            for (i in 0 until dishes.length()) { val x = dishes.getJSONObject(i); db.insertOrThrow("dishes", null, ContentValues().apply { put("id", x.getLong("id")); put("shop_id", x.getLong("shopId")); put("name", x.getString("name")); put("category", x.optString("category")); put("description", x.optString("description")); put("price_cents", x.getInt("price")); put("cloud_image_id", x.optString("imageId").ifBlank { null }) }) }
            val cart = root.optJSONArray("cart") ?: JSONArray()
            for (i in 0 until cart.length()) { val x = cart.getJSONObject(i); val note = x.optString("note"); db.insertOrThrow("cart", null, ContentValues().apply { put("cart_key", "${x.getLong("dishId")}:$note"); put("dish_id", x.getLong("dishId")); put("shop_id", x.getLong("shopId")); put("shop_name", x.getString("shopName")); put("dish_name", x.getString("dishName")); put("unit_price", x.getInt("price")); put("quantity", x.getInt("quantity")); put("note", note) }) }
            val orders = root.optJSONArray("orders") ?: JSONArray()
            for (i in 0 until orders.length()) { val x = orders.getJSONObject(i); val oid = x.getLong("id"); db.insertOrThrow("orders", null, ContentValues().apply { put("id", oid); put("created_at", x.getLong("createdAt")); put("total_cents", x.getInt("total")); put("item_count", x.getInt("count")) }); val lines = x.optJSONArray("lines") ?: JSONArray(); for (j in 0 until lines.length()) { val l = lines.getJSONObject(j); db.insertOrThrow("order_lines", null, ContentValues().apply { put("order_id", oid); put("shop_id", l.getLong("shopId")); put("shop_name", l.getString("shopName")); put("dish_id", l.getLong("dishId")); put("dish_name", l.getString("dishName")); put("unit_price", l.getInt("price")); put("quantity", l.getInt("quantity")); put("note", l.optString("note")) }) } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun pendingCloudImages(): List<CloudImageUpload> {
        val db = helper.readableDatabase
        return buildList {
            db.rawQuery("SELECT id,image_path FROM shops WHERE image_path IS NOT NULL AND cloud_image_id IS NULL", null).use { c -> while (c.moveToNext()) add(CloudImageUpload("shops", c.getLong(0), c.getString(1))) }
            db.rawQuery("SELECT id,image_path FROM dishes WHERE image_path IS NOT NULL AND cloud_image_id IS NULL", null).use { c -> while (c.moveToNext()) add(CloudImageUpload("dishes", c.getLong(0), c.getString(1))) }
        }
    }
    override fun cloudImagesNeedingDownload(): List<CloudImageUpload> {
        val db = helper.readableDatabase
        return buildList {
            db.rawQuery("SELECT id,cloud_image_id FROM shops WHERE image_path IS NULL AND cloud_image_id IS NOT NULL", null).use { c -> while (c.moveToNext()) add(CloudImageUpload("shops", c.getLong(0), c.getString(1))) }
            db.rawQuery("SELECT id,cloud_image_id FROM dishes WHERE image_path IS NULL AND cloud_image_id IS NOT NULL", null).use { c -> while (c.moveToNext()) add(CloudImageUpload("dishes", c.getLong(0), c.getString(1))) }
        }
    }
    override fun setCloudImageId(entity: String, id: Long, imageId: String) {
        require(entity == "shops" || entity == "dishes")
        helper.writableDatabase.update(entity, ContentValues().apply { put("cloud_image_id", imageId) }, "id=?", arrayOf(id.toString()))
    }
    override fun imagePath(entity: String, id: Long): String? {
        require(entity == "shops" || entity == "dishes")
        val field = if (entity == "shops") "image_path" else "image_path"
        return helper.readableDatabase.rawQuery("SELECT $field FROM $entity WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else null }
    }
    override fun setImagePath(entity: String, id: Long, path: String) {
        require(entity == "shops" || entity == "dishes")
        helper.writableDatabase.update(entity, ContentValues().apply { put("image_path", path) }, "id=?", arrayOf(id.toString()))
    }
    override fun resetForLogout() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            listOf("order_lines", "orders", "cart", "dishes", "shops").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        File(helperContext.filesDir, "menu-images").deleteRecursively()
        db.insertOrThrow("shops", null, ContentValues().apply { put("hall_id", 1); put("name", "荷园示例小炒"); put("description", "示例店铺，可在本机继续添加") }).also { shop ->
            db.insertOrThrow("dishes", null, ContentValues().apply { put("shop_id", shop); put("name", "招牌鸡腿饭"); put("category", "推荐"); put("description", "菜单示例"); put("price_cents", 1800) })
        }
    }

    private val helperContext = context.applicationContext
    private class MenuDb(context: Context) : SQLiteOpenHelper(context, "huanong_canteen.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE halls(id INTEGER PRIMARY KEY, name TEXT NOT NULL, sort_order INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE shops(id INTEGER PRIMARY KEY AUTOINCREMENT, hall_id INTEGER NOT NULL REFERENCES halls(id) ON DELETE CASCADE, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', image_path TEXT, cloud_image_id TEXT)")
            db.execSQL("CREATE TABLE dishes(id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT '推荐', description TEXT NOT NULL DEFAULT '', price_cents INTEGER NOT NULL, image_path TEXT, cloud_image_id TEXT)")
            db.execSQL("CREATE TABLE cart(cart_key TEXT PRIMARY KEY, dish_id INTEGER NOT NULL, shop_id INTEGER NOT NULL, shop_name TEXT NOT NULL, dish_name TEXT NOT NULL, unit_price INTEGER NOT NULL, quantity INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, total_cents INTEGER NOT NULL, item_count INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE order_lines(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE, shop_id INTEGER NOT NULL, shop_name TEXT NOT NULL, dish_id INTEGER NOT NULL, dish_name TEXT NOT NULL, unit_price INTEGER NOT NULL, quantity INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE INDEX idx_orders_created_at ON orders(created_at DESC,id DESC)")
            db.execSQL("CREATE INDEX idx_order_lines_order_id ON order_lines(order_id)")
            db.execSQL("PRAGMA foreign_keys=ON")
            listOf("荷园", "芷园", "莘园", "西园", "稻香园", "绿榕园", "小吃街", "外卖").forEachIndexed { index, name ->
                db.insertOrThrow("halls", null, ContentValues().apply { put("id", index + 1); put("name", name); put("sort_order", index) })
            }
            val shopId = db.insertOrThrow("shops", null, ContentValues().apply {
                put("hall_id", 1); put("name", "荷园示例小炒"); put("description", "示例店铺，可在本机继续添加")
            })
            db.insertOrThrow("dishes", null, ContentValues().apply {
                put("shop_id", shopId); put("name", "招牌鸡腿饭"); put("category", "推荐"); put("description", "菜单示例，名称和价格均可扩展编辑")
                put("price_cents", 1800)
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.insertOrThrow("halls", null, ContentValues().apply {
                put("id", 8); put("name", "外卖"); put("sort_order", 7)
            })
            if (oldVersion < 3) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at DESC,id DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_order_lines_order_id ON order_lines(order_id)")
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE shops ADD COLUMN cloud_image_id TEXT")
                db.execSQL("ALTER TABLE dishes ADD COLUMN cloud_image_id TEXT")
            }
        }
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
