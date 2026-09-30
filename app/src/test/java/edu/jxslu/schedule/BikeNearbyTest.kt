package edu.jxslu.schedule

import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.BikeStatus
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.domain.NearbyParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附近单车（DESIGN §4.23）：响应解析容错、聚簇口径、距离计算与排序、状态推导、距离文案。
 *
 * 样本取自 2026-09-23 对 `queryNearbyCar` 的实测响应（字段名与类型原样保留）。
 */
class BikeNearbyTest {

    private val centerLat = 28.688320
    private val centerLng = 116.028466

    private fun car(
        carNum: String = "100000652",
        lat: String = "28.6883209",
        lng: String = "116.0284657",
        battery: String = "92.8",
        lowBattery: String = "15.0",
        online: String = "1",
        status: String = "1",
        site: String = "教学北大楼左侧",
        campus: String = "南昌工程学院",
        model: String = "雅迪A8",
    ): String = """{"carNum":"$carNum","lat":$lat,"lng":$lng,"currentPercent":$battery,""" +
        """"lowBattery":$lowBattery,"onlineStatus":$online,"status":$status,""" +
        """"carTypeName":"$model","givecarName":"$site","servicesiteName":"$campus",""" +
        """"sn":"862965071723309","bluetoothKey":"","bluetoothName":"xlc-071723309"}"""

    private fun response(vararg cars: String, errorCode: String = "0"): String =
        """{"result":{"carList":[${cars.joinToString(",")}]},"resultCode":1,""" +
            """"errorCode":$errorCode,"time":1790142370916,"resultMsg":"请求成功"}"""

    private fun ok(raw: String): List<NearbyBike> {
        val parsed = BikeNearby.parse(raw, centerLat, centerLng)
        assertTrue("期望解析成功，实际 $parsed", parsed is NearbyParseResult.Ok)
        return (parsed as NearbyParseResult.Ok).bikes
    }

    // ---- 解析：正常与容错 ----

    @Test
    fun `解析实测响应`() {
        val bikes = ok(response(car()))
        assertEquals(1, bikes.size)
        val bike = bikes.first()
        assertEquals("100000652", bike.carNum)
        assertEquals(28.6883209, bike.lat, 1e-9)
        assertEquals(116.0284657, bike.lng, 1e-9)
        assertEquals(92.8, bike.batteryPercent!!, 1e-9)
        assertEquals(BikeStatus.Available, bike.status)
        assertTrue(bike.available)
        assertEquals("教学北大楼左侧", bike.siteName)
        assertEquals("南昌工程学院", bike.campusName)
        assertEquals("雅迪A8", bike.model)
    }

    @Test
    fun `数字字段写成字符串也认`() {
        val bikes = ok(
            response(
                car(
                    lat = "\"28.6883209\"",
                    lng = "\"116.0284657\"",
                    battery = "\"92.8\"",
                    lowBattery = "\"15.0\"",
                    online = "\"1\"",
                    status = "\"1\"",
                ),
            ),
        )
        assertEquals(1, bikes.size)
        assertEquals(92.8, bikes.first().batteryPercent!!, 1e-9)
        assertEquals(BikeStatus.Available, bikes.first().status)
    }

    @Test
    fun `服务端错误码归服务端错误`() {
        assertEquals(
            NearbyParseResult.ServiceError,
            BikeNearby.parse(response(car(), errorCode = "500"), centerLat, centerLng),
        )
    }

    @Test
    fun `errorCode 缺失也归服务端错误`() {
        assertEquals(
            NearbyParseResult.ServiceError,
            BikeNearby.parse("""{"result":{"carList":[]}}""", centerLat, centerLng),
        )
    }

    @Test
    fun `非 JSON 与结构不符归解析失败`() {
        assertEquals(
            NearbyParseResult.Malformed,
            BikeNearby.parse("<html>not json</html>", centerLat, centerLng),
        )
        assertEquals(
            NearbyParseResult.Malformed,
            BikeNearby.parse("""{"errorCode":0}""", centerLat, centerLng),
        )
        // result 在但没有 carList：接口改了形状，别显示成「这一带没有车」
        assertEquals(
            NearbyParseResult.Malformed,
            BikeNearby.parse("""{"errorCode":0,"result":{}}""", centerLat, centerLng),
        )
    }

    @Test
    fun `carList 为 null 或空数组都是空列表`() {
        assertEquals(
            emptyList<NearbyBike>(),
            ok("""{"errorCode":0,"result":{"carList":null}}"""),
        )
        assertEquals(emptyList<NearbyBike>(), ok(response()))
    }

    @Test
    fun `单条坏数据只丢这一条`() {
        val bikes = ok(
            response(
                car(carNum = "100000652"),
                // 车号非数字 → 出不了码，丢
                car(carNum = "abc"),
                // 车号位数不够 → 丢
                car(carNum = "669"),
                // 纬度越界 → 丢
                car(carNum = "100000653", lat = "128.6"),
                // 坐标全 0（接口用来表示"没有定位"）→ 丢
                car(carNum = "100000654", lat = "0", lng = "0"),
                // lat 写成非数字 → 丢
                car(carNum = "100000655", lat = "\"n/a\""),
            ),
        )
        assertEquals(listOf("100000652"), bikes.map { it.carNum })
    }

    @Test
    fun `非对象条目直接跳过`() {
        val bikes = ok("""{"errorCode":0,"result":{"carList":[null,1,"x",${car()}]}}""")
        assertEquals(listOf("100000652"), bikes.map { it.carNum })
    }

    // ---- 状态推导 ----

    @Test
    fun `离线与停用优先于电量`() {
        val offline = ok(response(car(online = "0", battery = "5"))).first()
        assertEquals(BikeStatus.Offline, offline.status)

        val disabled = ok(response(car(online = "1", status = "0", battery = "5"))).first()
        assertEquals(BikeStatus.Disabled, disabled.status)
    }

    @Test
    fun `电量低于阈值算电量低`() {
        assertEquals(BikeStatus.LowBattery, ok(response(car(battery = "15.0"))).first().status)
        assertEquals(BikeStatus.LowBattery, ok(response(car(battery = "9.9"))).first().status)
        // 刚好高于阈值仍算可用
        assertEquals(BikeStatus.Available, ok(response(car(battery = "15.1"))).first().status)
    }

    @Test
    fun `缺电量字段不把车说成电量低`() {
        // 上游实现里 currentPercent 缺省是 -1，配 lowBattery=15 会判成「电量低」，
        // 那会让一辆正常车在地图上变灰。这里要求缺数据时不参与电量判定
        val raw = """{"errorCode":0,"result":{"carList":[
            {"carNum":"100000652","lat":28.6883209,"lng":116.0284657,
             "lowBattery":15.0,"onlineStatus":1,"status":1}
        ]}}"""
        val bike = ok(raw).first()
        assertEquals(BikeStatus.Available, bike.status)
        assertEquals(null, bike.batteryPercent)
        assertEquals("电量未知", bike.batteryText)
    }

    @Test
    fun `电量是哨兵值或越界时按未知处理`() {
        // 上游把"没有数据"写成 -1：照收会显示成「-1%」并判成电量低，比不知道更糟
        val sentinel = ok(response(car(battery = "-1"))).first()
        assertEquals(null, sentinel.batteryPercent)
        assertEquals(BikeStatus.Available, sentinel.status)
        assertEquals("电量未知", sentinel.batteryText)

        // 超过 100 同样不可信
        assertEquals(null, ok(response(car(battery = "120"))).first().batteryPercent)

        // 阈值自己越界时整条电量判定都不参与，不拿一个坏阈值去说车
        val badThreshold = ok(response(car(battery = "5", lowBattery = "-1"))).first()
        assertEquals(BikeStatus.Available, badThreshold.status)
    }

    // ---- 聚簇 ----

    @Test
    fun `同一停车点聚成一簇`() {
        val bikes = ok(
            response(
                car(carNum = "100000652", lat = "28.6883209", lng = "116.0284657"),
                car(carNum = "100000345", lat = "28.6883210", lng = "116.0284658"),
                car(carNum = "100000023", lat = "28.6883215", lng = "116.0284660"),
            ),
        )
        val clusters = BikeNearby.cluster(bikes)
        assertEquals(1, clusters.size)
        assertEquals("教学北大楼左侧", clusters.first().title)
        assertEquals(3, clusters.first().bikes.size)
        // 展示坐标取成员均值
        assertEquals(bikes.map { it.lat }.average(), clusters.first().lat, 1e-9)
    }

    @Test
    fun `不同停车点分开成簇，顺序按最近距离`() {
        val bikes = ok(
            response(
                car(carNum = "100000652", lat = "28.6883209", lng = "116.0284657", site = "教学北大楼左侧"),
                car(carNum = "100000023", lat = "28.6863209", lng = "116.0264657", site = "学生公寓7栋"),
            ),
        )
        val clusters = BikeNearby.cluster(bikes)
        assertEquals(2, clusters.size)
        // 入参已按距离排序，簇的顺序跟着走
        assertEquals("教学北大楼左侧", clusters[0].title)
        assertEquals("学生公寓7栋", clusters[1].title)
    }

    @Test
    fun `没有停车点名时回落坐标网格`() {
        val bikes = ok(
            response(
                car(carNum = "100000652", lat = "28.6883209", lng = "116.0284657", site = ""),
                // 同一采样格（4 位小数）→ 归一组
                car(carNum = "100000023", lat = "28.6883249", lng = "116.0284699", site = ""),
                // 差得远 → 另一组
                car(carNum = "100000345", lat = "28.6863000", lng = "116.0264000", site = ""),
            ),
        )
        val clusters = BikeNearby.cluster(bikes)
        assertEquals(2, clusters.size)
        assertEquals(2, clusters[0].bikes.size)
        assertEquals("未标注停车点", clusters[0].title)
    }

    @Test
    fun `停车点名里的错别字被纠正`() {
        // 「济民楼」在运营方台账里有两种错法，都是 2026-09-23 实测拉到的原值
        assertEquals("济民楼", BikeNearby.correctSiteName("挤名楼"))
        assertEquals("济民楼", BikeNearby.correctSiteName("挤明楼"))
        // 不认识的名字原样返回，别自作主张改名
        assertEquals("教学北大楼左侧", BikeNearby.correctSiteName("教学北大楼左侧"))
        assertEquals("", BikeNearby.correctSiteName(""))
        // 纯数字是运营方的占位值（实测见过一个停车点叫 22222），当没写处理
        assertEquals("", BikeNearby.correctSiteName("22222"))
    }

    @Test
    fun `纯数字停车点名按未标注展示`() {
        val bikes = ok(
            response(car(carNum = "100000652", site = "22222")),
        )
        assertEquals("", bikes.first().siteName)
        assertEquals("未标注停车点", BikeNearby.cluster(bikes).first().title)
    }

    @Test
    fun `错别字在解析阶段就纠正，同一栋楼不会裂成两簇`() {
        val bikes = ok(
            response(
                car(carNum = "100000652", lat = "28.6883209", lng = "116.0284657", site = "挤名楼"),
                car(carNum = "100000023", lat = "28.6883210", lng = "116.0284658", site = "挤明楼"),
            ),
        )
        // 簇键取的是纠正后的名字，两种错法自然并成一组
        val clusters = BikeNearby.cluster(bikes)
        assertEquals(1, clusters.size)
        assertEquals("济民楼", clusters.first().title)
        assertEquals(2, clusters.first().bikes.size)
    }

    // ---- 距离与文案 ----

    @Test
    fun `采样点覆盖中心与一圈八方位`() {
        val points = BikeNearby.samplePoints(28.688320, 116.028466)
        assertEquals(9, points.size)
        // 第一个是中心，原样返回
        assertEquals(28.688320, points.first().lat, 1e-9)
        assertEquals(116.028466, points.first().lng, 1e-9)
        // 其余八个都落在采样半径上
        points.drop(1).forEach { point ->
            val distance = BikeNearby.distanceMeters(28.688320, 116.028466, point.lat, point.lng)
            assertEquals(BikeNearby.SAMPLE_RADIUS_METERS, distance, 2.0)
        }
        // 八个方位互不重合，且对面两点拉开整整一个直径
        assertEquals(8, points.drop(1).distinct().size)
        val opposite = BikeNearby.distanceMeters(
            points[1].lat, points[1].lng, points[5].lat, points[5].lng,
        )
        assertEquals(BikeNearby.SAMPLE_RADIUS_METERS * 2, opposite, 3.0)
    }

    @Test
    fun `采样点可以只要四个对角点`() {
        val points = BikeNearby.samplePoints(28.688320, 116.028466, ringCount = 4)
        assertEquals(5, points.size)
        assertEquals(28.688320, points.first().lat, 1e-9)
        // 四个对角点：每个都同时偏离中心的纬度与经度（正东西南北那四个会有一个不变）
        points.drop(1).forEach { point ->
            assertNotEquals(28.688320, point.lat, 1e-6)
            assertNotEquals(116.028466, point.lng, 1e-6)
            val distance = BikeNearby.distanceMeters(28.688320, 116.028466, point.lat, point.lng)
            assertEquals(BikeNearby.SAMPLE_RADIUS_METERS, distance, 2.0)
        }
        assertEquals(4, points.drop(1).distinct().size)
    }

    @Test
    fun `采样点数被夹在四到八之间`() {
        // 0 会退化成"只查中心"（z17 的视野比采样环还大，覆盖会明显掉），所以夹到 4
        assertEquals(5, BikeNearby.samplePoints(28.688320, 116.028466, ringCount = 0).size)
        assertEquals(5, BikeNearby.samplePoints(28.688320, 116.028466, ringCount = 3).size)
        assertEquals(9, BikeNearby.samplePoints(28.688320, 116.028466, ringCount = 16).size)
    }

    @Test
    fun `reanchor 换参照点后距离与顺序都跟着变`() {
        // 纬度 0.001 度约 111.2 米
        val raw = """{"errorCode":0,"result":{"carList":[
            {"carNum":"100000001","lat":28.6893209,"lng":116.0284657,
             "onlineStatus":1,"status":1,"givecarName":"A"},
            {"carNum":"100000002","lat":28.6863209,"lng":116.0284657,
             "onlineStatus":1,"status":1,"givecarName":"B"}
        ]}}"""
        val fromCenter = ok(raw)
        // 以校园中心（28.688320）为参照：A 约 111 米，B 约 222 米
        assertEquals(listOf("100000001", "100000002"), fromCenter.map { it.carNum })

        // 把参照点挪到南边，顺序该反过来，距离也按新参照点算
        val fromSouth = BikeNearby.reanchor(fromCenter, 28.6843209, 116.0284657)
        assertEquals(listOf("100000002", "100000001"), fromSouth.map { it.carNum })
        assertEquals(222.0, fromSouth.first().distanceMeters.toDouble(), 2.0)
    }

    @Test
    fun `电量低于档位才算偏低`() {
        assertTrue(ok(response(car(battery = "29.9"))).first().batteryLow)
        assertFalse(ok(response(car(battery = "30.0"))).first().batteryLow)
        assertFalse(ok(response(car(battery = "100"))).first().batteryLow)
    }

    @Test
    fun `电量低于20才算特殊标注档`() {
        assertTrue(ok(response(car(battery = "19.9"))).first().batteryLowBadge)
        assertFalse(ok(response(car(battery = "20.0"))).first().batteryLowBadge)
        // 30 档只管数字着色，不带图标标签
        val twentyFive = ok(response(car(battery = "25.0"))).first()
        assertTrue(twentyFive.batteryLow)
        assertFalse(twentyFive.batteryLowBadge)
        // 缺电量数据不标：缺数据不该变成警告（与 batteryLow 同一容错口径）
        val raw = """{"errorCode":0,"result":{"carList":[
            {"carNum":"100000652","lat":28.6883209,"lng":116.0284657,
             "onlineStatus":1,"status":1}
        ]}}"""
        val unknown = ok(raw).first()
        assertFalse(unknown.batteryLowBadge)
    }

    @Test
    fun `电量未知不算偏低`() {
        val raw = """{"errorCode":0,"result":{"carList":[
            {"carNum":"100000652","lat":28.6883209,"lng":116.0284657,
             "onlineStatus":1,"status":1}
        ]}}"""
        val bike = ok(raw).first()
        assertEquals(null, bike.batteryPercent)
        assertFalse("缺数据不该变成一个警告", bike.batteryLow)
    }

    @Test
    fun `haversine 距离`() {
        // 赤道上 1 度经度 ≈ 111.19 公里
        assertEquals(111195.0, BikeNearby.distanceMeters(0.0, 0.0, 0.0, 1.0), 2.0)
        assertEquals(0.0, BikeNearby.distanceMeters(centerLat, centerLng, centerLat, centerLng), 1e-6)
        // 同一停车点里的几米级位移也要算得出来，别被取整吃掉
        val near = BikeNearby.distanceMeters(28.6883209, 116.0284657, 28.6883300, 116.0284657)
        assertTrue("相邻车位的距离应该在 0~5 米内，实际 $near", near in 0.0..5.0)
    }

    @Test
    fun `按距离排序，同距离按车号`() {
        val bikes = ok(
            response(
                car(carNum = "100000900", lat = "28.6863209", lng = "116.0264657"),
                car(carNum = "100000200", lat = "28.6883209", lng = "116.0284657"),
                car(carNum = "100000100", lat = "28.6883209", lng = "116.0284657"),
            ),
        )
        assertEquals(listOf("100000100", "100000200", "100000900"), bikes.map { it.carNum })
    }

    @Test
    fun `距离文案`() {
        assertEquals("0 米", BikeNearby.formatDistance(0))
        assertEquals("240 米", BikeNearby.formatDistance(240))
        assertEquals("999 米", BikeNearby.formatDistance(999))
        assertEquals("1.0 公里", BikeNearby.formatDistance(1000))
        assertEquals("1.9 公里", BikeNearby.formatDistance(1908))
    }

    @Test
    fun `电量文案取整`() {
        val bike = ok(response(car(battery = "92.8"))).first()
        assertEquals("93%", bike.batteryText)
    }

    // ---- 校区过滤与围栏（DESIGN §3.9「只看本校」） ----

    @Test
    fun `校区名白名单按关键词匹配`() {
        // 2026-09-27 实测：本校车辆台账里是旧校名「南昌工程学院」
        assertTrue(BikeNearby.isOurCampus("南昌工程学院"))
        // 学校已更名「江西水利电力大学」，运营方将来更新台账也要认
        assertTrue(BikeNearby.isOurCampus("江西水利电力大学"))
        // 隔壁江西师大与快趣可能铺到的第三所学校都要挡掉
        assertFalse(BikeNearby.isOurCampus("江西师大"))
        assertFalse(BikeNearby.isOurCampus("江西外语外贸职业学院"))
        // 缺数据按本校算：不能因为台账没填就把一辆可能是本校的车藏掉
        assertTrue(BikeNearby.isOurCampus(""))
    }

    @Test
    fun `围栏覆盖本校停车点`() {
        // 取自 2026-09-27 探测的车队坐标四至与贴边停车点
        val oursInside = listOf(
            28.688320 to 116.028466, // 教学北大楼（默认中心）
            28.686400 to 116.024500, // 西大门
            28.685673 to 116.024845, // 南缘
            28.694637 to 116.031595, // 北缘（瑶湖西二路南侧停车带）
            28.687906 to 116.035264, // 东缘（三食堂一带）
            28.691195 to 116.026486, // 西北宿舍区（天祥大道贴边）
            28.693116 to 116.033746, // 东北
            // 东南角落：v4 南边曾在此偏北约 60 米、把校园南带漏在栏外（2026-09-27 真机反馈）
            28.684110 to 116.037800,
        )
        oursInside.forEach { (lat, lng) ->
            assertTrue("($lat, $lng) 应在围栏内", BikeNearby.inCampusFence(lat, lng))
        }
    }

    @Test
    fun `围栏覆盖全部实测车辆点位`() {
        // 2026-09-27 探测到的 131 个本校车队坐标（测试资源文件，头注释有说明）。
        // 上面那组代表点只防大偏移，这份全量清单才是围栏顶点真正的"验收线"：
        // 拟合顶点时就是按"131 点全在栏内"收的口，挑点改动后重跑本用例即可回归。
        val text = checkNotNull(javaClass.getResourceAsStream("/campus_fence_bikes_20260927.txt")) {
            "缺少测试资源 campus_fence_bikes_20260927.txt"
        }.bufferedReader().use { it.readText() }
        val points = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0].toDouble() to parts[1].toDouble()
            }
            .toList()
        assertTrue("fixture 应含全部实测点位（≥100），当前 ${points.size}", points.size >= 100)
        val outside = points.filter { (lat, lng) -> !BikeNearby.inCampusFence(lat, lng) }
        assertTrue("实测点位应全部在栏内，栏外 ${outside.size} 个：$outside", outside.isEmpty())
    }

    @Test
    fun `围栏挡住师大与周边院校`() {
        val outsiders = listOf(
            28.684281 to 116.029508, // 师大北缘（两校车队坐标最近处）
            28.683300 to 116.033500, // 师大宿舍区（17栋一带）
            28.681465 to 116.024354, // 师大西侧
            28.690000 to 116.023000, // 江西工业职院（天祥大道西侧）
            28.682000 to 116.009000, // 远处（校外）
            // 东北角路口东北侧：v4 的东北角曾越过路口伸到这里（2026-09-27 真机反馈）
            28.693650 to 116.037700,
            // 西北角路口外（天祥大道×瑶湖西二路 西北象限）
            28.696200 to 116.027300,
        )
        outsiders.forEach { (lat, lng) ->
            assertFalse("($lat, $lng) 应在围栏外", BikeNearby.inCampusFence(lat, lng))
        }
    }

    @Test
    fun `只看本校要求车队与围栏双条件都过`() {
        // 本校的车停在校内：过
        assertTrue(BikeNearby.isOurCampusBike(28.68832, 116.028466, "南昌工程学院"))
        // 校区名没填但停在校内：围栏兜底放行
        assertTrue(BikeNearby.isOurCampusBike(28.68832, 116.028466, ""))
        // 台账挂了本校名但停在师大校园（挂错或骑走未回桩）：围栏拦下
        assertFalse(BikeNearby.isOurCampusBike(28.6833, 116.0335, "南昌工程学院"))
        // 师大的车就算坐标落进围栏也进不来：车队归属不过
        assertFalse(BikeNearby.isOurCampusBike(28.68832, 116.028466, "江西师大"))
        // 校区名缺失又停在栏外：两个条件都不过
        assertFalse(BikeNearby.isOurCampusBike(28.6843, 116.0295, ""))
    }

    @Test
    fun `解析层保留校区字段供过滤`() {
        val ours = ok(response(car(campus = "南昌工程学院"))).first()
        assertEquals("南昌工程学院", ours.campusName)
        val jxnu = ok(response(car(campus = "江西师大"))).first()
        assertEquals("江西师大", jxnu.campusName)
        assertFalse(BikeNearby.isOurCampus(jxnu.campusName))
    }

    // ---- 单车详情（queryOneCar，2026-09-30） ----

    /** 单车详情响应：`result` 直接是车辆对象（官方 `loadOneCar` 消费五字段同款）。 */
    private fun single(
        carJson: String,
        errorCode: String = "0",
    ): String = """{"result":$carJson,"resultCode":1,"errorCode":$errorCode,"resultMsg":"请求成功"}"""

    @Test
    fun `单车详情解析同构响应`() {
        val bike = BikeNearby.parseSingle(single(car()), centerLat, centerLng)
        assertNotNull(bike)
        assertEquals("100000652", bike!!.carNum)
        assertEquals(28.6883209, bike.lat, 1e-9)
        assertEquals(92.8, bike.batteryPercent!!, 1e-9)
        assertEquals(BikeStatus.Available, bike.status)
        // 距离按查询中心本地算：fixture 的车就停在中心
        assertTrue(bike.distanceMeters < 5)
    }

    @Test
    fun `单车详情缺在线启用字段不当成失联`() {
        // 详情接口的字段口径只确认过五个（carNum/lat/lng/deviceType/currentPercent）；
        // onlineStatus/status 缺了就当在线可用——用户是拿完整车号点名查这辆车，
        // 缺数据不该把它标成"失联"（与附近列表"坏了丢一条"的口径不同）
        val raw = """{"result":{"carNum":"100000652","lat":$centerLat,"lng":$centerLng,
            "currentPercent":"64"},"resultCode":1,"errorCode":0}"""
        val bike = BikeNearby.parseSingle(raw, centerLat, centerLng)
        assertNotNull(bike)
        assertEquals(BikeStatus.Available, bike!!.status)
        assertEquals(64.0, bike.batteryPercent!!, 1e-9)
    }

    @Test
    fun `单车详情明确离线与停用照旧生效`() {
        val offline = BikeNearby.parseSingle(single(car(online = "0")), centerLat, centerLng)
        assertEquals(BikeStatus.Offline, offline?.status)
        val disabled = BikeNearby.parseSingle(single(car(status = "0")), centerLat, centerLng)
        assertEquals(BikeStatus.Disabled, disabled?.status)
    }

    @Test
    fun `单车详情坏车号与业务错误返回 null`() {
        // 出不了码的车号：与附近列表同一道闸
        assertNull(BikeNearby.parseSingle(single(car(carNum = "abc")), centerLat, centerLng))
        // 业务错误（errorCode != 0）
        assertNull(
            BikeNearby.parseSingle(
                """{"resultCode":1,"errorCode":30001,"resultMsg":"车辆不存在","result":null}""",
                centerLat, centerLng,
            ),
        )
        // 非 JSON
        assertNull(BikeNearby.parseSingle("<html>", centerLat, centerLng))
    }
}
