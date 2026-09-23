package edu.jxslu.schedule

import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.domain.BikeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 附近单车接口客户端的失败分类（DESIGN §4.23）。
 *
 * 超时是 [IOException] 的子类，判断顺序反了就会把超时吃成「网络不可达」，
 * 用户按提示反复检查网络也修不好——这几条就是在钉这个顺序。
 */
class KqcxBikeClientTest {

    @Test
    fun `超时归超时`() {
        assertEquals(BikeFailure.Timeout, KqcxBikeClient.classify(SocketTimeoutException("timeout")))
    }

    @Test
    fun `连不上归网络`() {
        assertEquals(BikeFailure.Network, KqcxBikeClient.classify(IOException("boom")))
        assertEquals(
            BikeFailure.Network,
            KqcxBikeClient.classify(UnknownHostException("api.kvcoogo.com")),
        )
    }

    @Test
    fun `非 2xx 归服务端`() {
        assertEquals(
            BikeFailure.Service,
            KqcxBikeClient.classify(KqcxBikeClient.ServiceHttpException(502)),
        )
    }

    @Test
    fun `意料之外的异常也算一次失败，不往外抛`() {
        assertEquals(BikeFailure.Network, KqcxBikeClient.classify(IllegalStateException("boom")))
    }
}