/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.blocking

/**
 * The "SMS Screening Protocol": an SMS app binds a screening provider's exported service and
 * asks, per message, whether it should be blocked. Messages go over a [android.os.Messenger].
 *
 * Spec: https://github.com/aj3423/SpamBlocker/wiki/SMS-Screening-Protocol
 */
object SmsScreeningProtocol {

    /** Intent action of the provider's service; also what the manifest `<queries>` block declares */
    const val ACTION = "sms.screening.provider.PublicSMSScreeningService"

    /** Message.what of the request */
    const val WHAT_SCREEN = 1

    /** Message.what of the reply */
    const val WHAT_RESULT = 2

    // Request extras
    const val KEY_NUMBER = "number"
    const val KEY_SMS_CONTENT = "smsContent"
    const val KEY_SIM_SLOT = "simSlot"

    // Reply extras
    const val KEY_SHOULD_BLOCK = "shouldBlock"
    const val KEY_REASON = "reason"

}
