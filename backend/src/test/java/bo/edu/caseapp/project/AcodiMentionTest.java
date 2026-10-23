package bo.edu.caseapp.chat;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class AcodiMentionTest {
  @Test void explicitMentionsAreCaseInsensitiveAtAnyPosition() {
    for(String text:new String[]{"@ACODI revisa mi diagrama","@acodi ¿qué falta?","Hola @ACODI, analiza esta relación","(@AcOdI) revisa","@ACODI"}) assertTrue(AcodiMention.present(text),text);
  }
  @Test void excludesEmailsPartialWordsAndPlainNames() {
    for(String text:new String[]{"ACODI revisa","Carlos revisa","persona@acodi.com","x@ACODI","@ACODIgenera","@ACODI_2","@ACODI-extra","@@ACODI","á@ACODI"}) assertFalse(AcodiMention.present(text),text);
    assertFalse(AcodiMention.present(null));
  }
  @Test void removesOnlyValidMentionsLocally() { assertEquals("Hola , revisa Venta",AcodiMention.question("Hola @acodi, revisa Venta"));assertEquals("persona@acodi.com",AcodiMention.question("persona@acodi.com")); }
}
