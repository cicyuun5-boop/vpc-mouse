package cc.vpc.hidmouse

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties

/** 取本机 Wi-Fi 的 IPv4，省得用户自己去设置里翻 */
object NetUtil {

    fun localIpv4(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "未联网"
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return "未联网"
        for (addr in lp.linkAddresses) {
            val ip = addr.address
            if (!ip.isLoopbackAddress && ip.address.size == 4) return ip.hostAddress ?: "未知"
        }
        return "未知"
    }
}
