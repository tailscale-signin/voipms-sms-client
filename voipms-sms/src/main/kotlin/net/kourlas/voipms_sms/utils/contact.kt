/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.demo.getNewConversationContacts
import java.util.Locale

private const val TAG = "Contact"

/**
 * Gets the name of a contact from the Android contacts provider, given a
 * phone number.
 */
fun getContactName(
    context: Context, phoneNumber: String,
    contactNameCache: MutableMap<String, String>? = null
): String? {
    try {
        if (contactNameCache != null && phoneNumber in contactNameCache) {
            return contactNameCache[phoneNumber]
        }
        if (BuildConfig.IS_DEMO) {
            for (contactItem in getNewConversationContacts()) {
                if (contactItem.primaryPhoneNumber == phoneNumber) {
                    return contactItem.name
                }
            }
            return null
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            ),
            null, null, null
        )
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        ContactsContract.PhoneLookup.DISPLAY_NAME
                    )
                )
                cursor.close()
                if (contactNameCache != null && name != null) {
                    contactNameCache[phoneNumber] = name
                }
                return name
            } else {
                cursor.close()
            }
        }
        return null
    } catch (_: SecurityException) {
        // The app does not have the contacts permission.
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get contact name", e)
        return null
    }
}

/** The same initial represents a contact in the inbox, thread, picker and conversation shortcut. */
fun getContactPhotoBitmap(context: Context, name: String?, size: Int): Bitmap {
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = ContextCompat.getColor(context, R.color.message_outgoing)
    canvas.drawCircle(center, center, center, paint)
    paint.color = ContextCompat.getColor(context, R.color.primary)
    paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    paint.textSize = size * .49f
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(getContactInitial(name), center, center - (paint.ascent() + paint.descent()) / 2, paint)
    return bitmap
}

fun getContactPhotoAdaptiveBitmap(context: Context, name: String?): Bitmap {
    val bitmap = getContactPhotoBitmap(context, name,
        context.resources.getDimensionPixelSize(R.dimen.adaptive_icon_drawable_inner))
    val size = context.resources.getDimensionPixelSize(R.dimen.adaptive_icon_drawable_outer)
    return createBitmap(size, size).also { adaptive ->
        val offset = (size - bitmap.width) / 2f
        Canvas(adaptive).drawBitmap(bitmap, offset, offset, null)
    }
}

fun getContactInitial(name: String?): String {
    val text = name.orEmpty().trimStart()
    if (text.isEmpty()) return "#"
    var at = 0
    while (at < text.length && !Character.isLetterOrDigit(text.codePointAt(at))) {
        at += Character.charCount(text.codePointAt(at))
    }
    if (at == text.length || !Character.isLetter(text.codePointAt(at))) return "#"
    val characters = java.text.BreakIterator.getCharacterInstance(Locale.getDefault())
    characters.setText(text)
    return text.substring(at, characters.following(at)).uppercase(Locale.getDefault())
}
