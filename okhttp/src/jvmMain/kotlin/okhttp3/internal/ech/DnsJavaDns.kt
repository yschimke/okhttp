/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package okhttp3.internal.ech

import android.annotation.SuppressLint
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns
import org.xbill.DNS.ARecord
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Name
import org.xbill.DNS.Type
import org.xbill.DNS.lookup.LookupSession

@SuppressLint("NewApi")
class DnsJavaDns(val s: LookupSession) : Dns {
  val httpsRecords = ConcurrentHashMap<String, HTTPSRecord>()

  override fun lookup(hostname: String): List<InetAddress> {
    val mxLookup = Name.fromString("${hostname}.")

    println(mxLookup)

    val resultHTTPS = s.lookupAsync(mxLookup, Type.HTTPS).toCompletableFuture()
    val resultA = s.lookupAsync(mxLookup, Type.A).toCompletableFuture()
    val resultAAAA = s.lookupAsync(mxLookup, Type.AAAA).toCompletableFuture()

    println(resultAAAA.get())
    println(resultA.get())
    println(resultHTTPS.get())

    val https = resultHTTPS.get().records.firstOrNull() as HTTPSRecord?

    if (https != null) {
      httpsRecords[hostname] = https
    } else {
      println("No HTTPS record for $hostname")
    }

    // resultAAAA.get().records.map { (it as AAAARecord).address } +
    return resultA.get().records.map { (it as ARecord).address }
  }
}
