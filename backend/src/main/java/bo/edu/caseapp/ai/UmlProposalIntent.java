package bo.edu.caseapp.ai;

import java.text.Normalizer;
import java.util.Locale;

/** Conservative local routing. A chat message can prepare a preview, never approve it. */
public final class UmlProposalIntent {
  public enum Intent { DISCUSS, PREPARE, DECIDE }
  private UmlProposalIntent() { }
  public static Intent classify(String content) {
    String text=normalize(content);
    if(text.matches("^(si[, ]*|por favor[, ]*)?(aplica|aplicar|acepta|aceptar|rechaza|rechazar|confirma|confirmar)\\b.*")
        || text.matches("^(si|ok|dale|adelante|hazlo)[.! ]*$")) return Intent.DECIDE;
    if(text.matches("^(prepar[ae]r?|prepares|propon(?:e|er|gas)|gener[ae]r?|generes|cre[ae]r?|crees|agreg[ae]r?|agregues|anad(?:e|ir|as)|modific(?:a|ar|aramos)|modifiques|actualiz(?:a|ar)|actualices|cambi(?:a|ar|es)|elimin(?:a|ar|es|e)|borr(?:a|ar|es)|quit(?:a|ar|es)|suprim(?:e|ir|as)|remov(?:er)|remuev(?:e|as)|desconect(?:a|ar|es)|relacion(?:a|ar|es))\\b.*")) return Intent.PREPARE;
    return Intent.DISCUSS;
  }
  public static String normalize(String content) {
    String text=Normalizer.normalize(content==null?"":content.trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
    text=text.replaceAll("(?<![\\w@.])@acodi\\b", "").replaceFirst("^[¿¡,;:\\s]+", "");
    for(int i=0;i<5;i++) text=text.replaceFirst("^(?:(?:hola|acodi|ia|agente|asistente|por favor)[,: ]+|(?:quiero que|necesito que|me gustaria que|quisiera que|me puedes|me podrias|puedes|podrias|ayudame a|quiero|necesito|quisiera|vamos a)\\s+)", "").stripLeading();
    return text;
  }
  public static boolean deletion(String content) { return normalize(content).matches("^(elimin\\w*|borr\\w*|quit\\w*|suprim\\w*|remov\\w*|remuev\\w*|desconect\\w*)\\b.*"); }
}
