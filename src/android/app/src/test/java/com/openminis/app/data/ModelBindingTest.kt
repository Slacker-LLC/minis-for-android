package com.openminis.app.data

import com.openminis.app.data.model.ModelBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelBindingTest {
    @Test
    fun `group binding with last entry is parsed`() {
        assertEquals(ModelBinding.Group("g-1", "entry-2"), ModelBinding.parse("""{"type":"group","groupId":"g-1","lastEntryId":"entry-2"}"""))
    }

    @Test
    fun `group binding without last entry is parsed`() {
        assertEquals(ModelBinding.Group("g-1"), ModelBinding.parse("""{"type":"group","groupId":"g-1"}"""))
    }

    @Test
    fun `entry binding is parsed`() {
        assertEquals(ModelBinding.Entry("entry-1"), ModelBinding.parse("""{"type":"entry","entryId":"entry-1"}"""))
    }

    @Test
    fun `malformed incomplete and unknown bindings fail closed`() {
        assertNull(ModelBinding.parse("{"))
        assertNull(ModelBinding.parse("""{"type":"group"}"""))
        assertNull(ModelBinding.parse("""{"type":"future","entryId":"e"}"""))
    }
}
