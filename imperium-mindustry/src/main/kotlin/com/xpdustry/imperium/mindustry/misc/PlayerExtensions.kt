/*
 * Imperium, the software collection powering the Xpdustry network.
 * Copyright (C) 2023  Xpdustry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.xpdustry.imperium.mindustry.misc

import com.xpdustry.imperium.common.misc.toInetAddress
import com.xpdustry.imperium.common.security.Identity
import java.time.Instant
import mindustry.gen.Call
import mindustry.gen.Player

val Player.identity: Identity.Mindustry
    get() = Identity.Mindustry(info.plainLastName(), uuid(), usid(), con.address.toInetAddress())

val Player.joinTime: Instant
    get() = Instant.ofEpochMilli(con.connectTime)

fun Player.showInfoMessage(message: String) = Call.infoMessage(con, message)
