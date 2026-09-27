package kr.chanhee.eml2txt

import kr.chanhee.eml2txt.EmlConverter.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs on a computer (and on GitHub before every build): `./gradlew test`. */
class EmlConverterTest {

    // A chat export in the exact shape of a real one: no BOM, CRLF between messages,
    // bare LF inside a multi-line message.
    private val chat = "홍길동 님과 카카오톡 대화\r\n" +
        "저장한 날짜 : 2026년 9월 26일 오후 9:30\n" +
        "\r\n\r\n" +
        "2026년 9월 2일 오후 5:23\r\n" +
        "2026년 9월 2일 오후 5:23, 홍길동 : 안녕하세요 👋\r\n" +
        "2026년 9월 2일 오후 5:24, 회원님 : [장소]\n서울 강남구\nhttps://example.com/x\r\n" +
        "2026년 9월 2일 오후 5:25, 홍길동 : 좋아요!\r\n"

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val cp949 = Charset.forName("x-windows-949")
    private val headerOnly = Options(header = true, body = false, attachments = false)
    private val attachmentsOnly = Options(header = false, body = false, attachments = true)

    private fun b64(bytes: ByteArray) = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)
    private fun b64word(s: String) = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray()) + "?="

    private fun mail(vararg parts: String, headers: String = ""): ByteArray {
        val sb = StringBuilder()
        sb.append("From: ${b64word("정찬희")} <me@example.com>\r\nTo: you@example.com\r\n")
        sb.append("Date: Sun, 27 Sep 2026 11:00:00 +0900\r\nSubject: ${b64word("테스트 메일")}\r\n")
        sb.append(headers)
        sb.append("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"XYZ\"\r\n\r\n")
        for (p in parts) sb.append("--XYZ\r\n").append(p).append("\r\n")
        sb.append("--XYZ--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8) // raw 8-bit names are UTF-8 in practice
    }

    private val plainBody = "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
        Base64.getEncoder().encodeToString("본문입니다.\r\n둘째 줄".toByteArray())

    private fun textAttachment(name: String, content: ByteArray) =
        "Content-Type: text/plain; name=\"$name\"\r\nContent-Disposition: attachment; filename=\"$name\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" + b64(content)

    private fun email(bytes: ByteArray) = EmlConverter.parse(bytes) as EmlConverter.Email

    // ---------------------------------------------------------------- plain-text files named .eml

    @Test fun plainTextFileIsSavedExactlyAsIs() {
        val doc = EmlConverter.parse(chat.toByteArray())
        assertTrue(doc is EmlConverter.PlainText)
        assertEquals(chat, EmlConverter.render(doc, Options()))
        assertEquals(10, EmlConverter.lineCount(EmlConverter.render(doc, Options())))
    }

    @Test fun plainTextBomIsDroppedAndCp949Decoded() {
        assertEquals(chat, EmlConverter.render(EmlConverter.parse(bom + chat.toByteArray()), Options()))
        val noEmoji = chat.replace(" 👋", "")
        assertEquals(noEmoji, EmlConverter.render(EmlConverter.parse(noEmoji.toByteArray(cp949)), Options()))
    }

    @Test fun emptyAndBinaryFilesAreRejected() {
        for ((bytes, reason) in listOf(
            ByteArray(0) to EmlConverter.Reason.EMPTY_FILE,
            "\r\n\r\n".toByteArray() to EmlConverter.Reason.EMPTY_FILE,
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13) to EmlConverter.Reason.NOT_TEXT,
        )) {
            try {
                EmlConverter.parse(bytes); fail("expected $reason")
            } catch (e: EmlConverter.ConversionException) {
                assertEquals(reason, e.reason)
            }
        }
    }

    // ---------------------------------------------------------------- real e-mails

    @Test fun fullEmailWithTextAttachment() {
        val doc = email(mail(plainBody, textAttachment("대화.txt", bom + chat.toByteArray())))
        val size = (bom + chat.toByteArray()).size
        val expected = "From: 정찬희 <me@example.com>\n" +
            "To: you@example.com\n" +
            "Date: Sun, 27 Sep 2026 11:00:00 +0900\n" +
            "Subject: 테스트 메일\n" +
            "Attachments: 대화.txt ($size B)\n" +
            "\n" +
            "본문입니다.\n둘째 줄\n" +
            "\n" +
            "===== 대화.txt =====\n" +
            chat.replace("\r\n", "\n")
        assertEquals(expected, EmlConverter.render(doc, Options()))
    }

    @Test fun singleAttachmentAloneHasNoTitleLine() {
        val doc = email(mail(plainBody, textAttachment("chat_export.txt", bom + chat.toByteArray())))
        assertEquals(chat.replace("\r\n", "\n"), EmlConverter.render(doc, attachmentsOnly))
    }

    @Test fun headerOnlyAndNothingSelected() {
        val doc = email(mail(plainBody))
        assertEquals(
            "From: 정찬희 <me@example.com>\nTo: you@example.com\nDate: Sun, 27 Sep 2026 11:00:00 +0900\nSubject: 테스트 메일\n",
            EmlConverter.render(doc, headerOnly),
        )
        assertEquals("", EmlConverter.render(doc, Options(false, false, false)))
    }

    @Test fun alternativePrefersPlainTextButKeepsAttachmentsFromTheHtmlBranch() {
        // Apple Mail style: the attachment sits inside the HTML alternative
        val eml = ("From: a@b.c\r\nSubject: hi\r\nContent-Type: multipart/alternative; boundary=\"A\"\r\n\r\n" +
            "--A\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nplain version\r\n" +
            "--A\r\nContent-Type: multipart/mixed; boundary=\"B\"\r\n\r\n" +
            "--B\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>html version</p>\r\n" +
            "--B\r\n" + textAttachment("notes.txt", "note text".toByteArray()) + "\r\n--B--\r\n" +
            "--A--\r\n").toByteArray()
        val m = email(eml).message
        assertEquals("plain version", m.body)
        assertEquals(listOf("notes.txt"), m.attachments.map { it.name })
        assertEquals("note text", m.sections.single().text)
    }

    @Test fun htmlOnlyMailIsConvertedToText() {
        val eml = ("From: a@b.c\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
            "<html><head><style>p{}</style></head><body><p>첫 줄 &amp; 끝</p><div>둘째&nbsp;줄 &#x1F44D; &#1114112;</div></body></html>")
            .toByteArray()
        assertEquals("첫 줄 & 끝\n둘째 줄 👍 &#1114112;", email(eml).message.body)
    }

    @Test fun inlineLogoIsNotListedButRealImageAttachmentIs() {
        val eml = mail(
            plainBody,
            "Content-Type: image/png\r\nContent-ID: <logo@x>\r\nContent-Transfer-Encoding: base64\r\n\r\niVBORw0K",
            "Content-Type: image/jpeg; name=\"photo.jpg\"\r\nContent-Disposition: attachment; filename=\"photo.jpg\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n/9j/4AAQ",
        )
        val m = email(eml).message
        assertEquals(listOf("photo.jpg"), m.attachments.map { it.name })
        assertTrue(m.sections.isEmpty())
    }

    @Test fun koreanFileNameSplitAcrossEncodedWords() {
        val name = "카톡_대화.txt".toByteArray()
        val w1 = Base64.getEncoder().encodeToString(name.copyOfRange(0, 4))
        val w2 = Base64.getEncoder().encodeToString(name.copyOfRange(4, name.size))
        val eml = mail(
            "Content-Type: application/octet-stream; name=\"=?UTF-8?B?$w1?=\r\n =?UTF-8?B?$w2?=\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" + b64(chat.toByteArray()),
        )
        assertEquals(listOf("카톡_대화.txt"), email(eml).message.attachments.map { it.name })
    }

    @Test fun rfc2231FileNameAndOutlookKoreanCharset() {
        val eml = mail(
            "Content-Type: text/plain; charset=\"ks_c_5601-1987\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
                b64("한글 본문".toByteArray(cp949)),
            "Content-Type: text/plain\r\nContent-Disposition: attachment; filename*=UTF-8''%EB%A9%94%EB%AA%A8.txt\r\n\r\nmemo",
        )
        val m = email(eml).message
        assertEquals("한글 본문", m.body)
        assertEquals("메모.txt", m.sections.single().title)
    }

    @Test fun zipAttachmentTextEntriesInNaturalOrder() {
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos, cp949).use { z ->
                for ((n, t) in listOf("대화/Talk-10.txt" to "ten", "대화/Talk-2.txt" to "two", "대화/Talk-1.txt" to "one", "대화/사진.jpg" to "\u0000\u0001")) {
                    z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray())
                }
            }
        }.toByteArray()
        val eml = mail(
            "Content-Type: application/zip; name=\"chat.zip\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + b64(zip),
        )
        val m = email(eml).message
        assertEquals(listOf("chat.zip"), m.attachments.map { it.name })
        assertEquals(
            listOf("chat.zip > 대화/Talk-1.txt", "chat.zip > 대화/Talk-2.txt", "chat.zip > 대화/Talk-10.txt"),
            m.sections.map { it.title },
        )
        assertEquals(
            "===== chat.zip > 대화/Talk-1.txt =====\none\n\n===== chat.zip > 대화/Talk-2.txt =====\ntwo\n\n" +
                "===== chat.zip > 대화/Talk-10.txt =====\nten\n",
            EmlConverter.render(email(eml), attachmentsOnly),
        )
    }

    @Test fun forwardedMailIsShownWithItsOwnHeader() {
        val inner = "From: friend@example.com\r\nSubject: original\r\n\r\ninner text"
        val eml = mail(plainBody, "Content-Type: message/rfc822\r\n\r\n$inner")
        val out = EmlConverter.render(email(eml), Options())
        assertTrue(out, out.contains("===== Forwarded message =====\nFrom: friend@example.com\nSubject: original\n\ninner text"))
    }

    @Test fun plainTextEmlAttachmentIsTextNotAForwardedMail() {
        val eml = mail(plainBody, textAttachment("대화 내보내기.eml", chat.toByteArray()))
        val s = email(eml).message.sections.single()
        assertEquals(null, s.message)
        assertEquals(chat.replace("\r\n", "\n"), s.text)
    }

    @Test fun truncatedMailBomAndMboxLine() {
        val full = String(mail(plainBody, textAttachment("a.txt", "abc".toByteArray())), Charsets.UTF_8)
        val truncated = full.removeSuffix("--XYZ--\r\n")
        val weird = bom + "\r\nFrom me@example.com Sun Sep 27 11:00:00 2026\n".toByteArray() + truncated.toByteArray(Charsets.UTF_8)
        val m = email(weird).message
        assertEquals("테스트 메일", m.subject)
        assertEquals("abc", m.sections.single().text)
    }

    @Test fun oneBadByteDoesNotGarbleUtf8() {
        val bytes = chat.toByteArray()
        val broken = bytes.copyOfRange(0, 20) + byteArrayOf(0xFF.toByte()) + bytes.copyOfRange(20, bytes.size)
        val m = email(mail(textAttachment("chat.txt", broken))).message
        assertTrue(m.sections.single().text!!.contains("안녕하세요 👋"))
    }

    @Test fun iso2022jpBody() {
        val jp = Charset.forName("ISO-2022-JP")
        val eml = ("From: a@b.c\r\nContent-Type: text/plain; charset=iso-2022-jp\r\n\r\n").toByteArray() +
            "こんにちは".toByteArray(jp)
        assertEquals("こんにちは", email(eml).message.body)
    }

    @Test fun suggestedFileNames() {
        val plain = EmlConverter.parse(chat.toByteArray())
        val mail = EmlConverter.parse(mail(plainBody))
        assertEquals("backup.txt", EmlConverter.suggestFileName("backup.eml", plain))
        assertEquals("홍길동 님과 카카오톡 대화.txt", EmlConverter.suggestFileName("______.eml", plain))
        assertEquals("테스트 메일.txt", EmlConverter.suggestFileName("original_msg.txt", mail))
        assertEquals("테스트 메일.txt", EmlConverter.suggestFileName(null, mail))
        assertEquals("a_b.txt", EmlConverter.suggestFileName("a:b.eml", mail))
        assertFalse(EmlConverter.suggestFileName("x.eml", mail).contains('/'))
    }
}
