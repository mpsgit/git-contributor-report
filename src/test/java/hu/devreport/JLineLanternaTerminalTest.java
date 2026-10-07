package hu.devreport;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLineLanternaTerminalTest {
    @Test
    void mapsTabAndBothEnterRepresentations() {
        assertEquals(KeyType.Tab, JLineLanternaTerminal.decodeSimple('\t').getKeyType());
        assertEquals(KeyType.Enter, JLineLanternaTerminal.decodeSimple('\r').getKeyType());
        assertEquals(KeyType.Enter, JLineLanternaTerminal.decodeSimple('\n').getKeyType());
    }

    @Test
    void preservesHungarianUnicodeCharacters() {
        KeyStroke lower = JLineLanternaTerminal.decodeSimple('ő');
        KeyStroke upper = JLineLanternaTerminal.decodeSimple('Ű');

        assertEquals(Character.valueOf('ő'), lower.getCharacter());
        assertEquals(Character.valueOf('Ű'), upper.getCharacter());
        assertFalse(lower.isCtrlDown());
        assertFalse(lower.isAltDown());
    }

    @Test
    void mapsControlCharacters() {
        KeyStroke ctrlC = JLineLanternaTerminal.decodeSimple(3);

        assertEquals(Character.valueOf('c'), ctrlC.getCharacter());
        assertTrue(ctrlC.isCtrlDown());
    }

    @Test
    void recognizesAltMenuShortcutsCaseInsensitively() {
        assertEquals(Character.valueOf('f'),
                InteractiveConsole.menuShortcut(new KeyStroke('F', false, true)));
        assertEquals(Character.valueOf('b'),
                InteractiveConsole.menuShortcut(new KeyStroke('b', false, true)));
        assertEquals(Character.valueOf('s'),
                InteractiveConsole.menuShortcut(new KeyStroke('S', false, true)));
        assertEquals(null, InteractiveConsole.menuShortcut(new KeyStroke('f', false, false)));
    }
}
