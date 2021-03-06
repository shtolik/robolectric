package org.robolectric.shadows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.text.SpannedString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ShadowSpannedStringTest {

  @Test
  public void toString_shouldDelegateToUnderlyingCharSequence() {
    SpannedString spannedString = new SpannedString("foo");
    assertEquals("foo", spannedString.toString());
  }

  @Test
  public void valueOfSpannedString_shouldReturnItself() {
    SpannedString spannedString = new SpannedString("foo");
    assertSame(spannedString, SpannedString.valueOf(spannedString));
  }

  @Test
  public void valueOfCharSequence_shouldReturnNewSpannedString() {
    assertEquals("foo", SpannedString.valueOf("foo").toString());
  }


}

