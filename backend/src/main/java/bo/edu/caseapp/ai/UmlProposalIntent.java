package bo.edu.caseapp.ai;

import java.text.Normalizer;
import java.util.Locale;

/** Conservative local routing. A chat message can prepare a preview, never approve it. */
public final class UmlProposalIntent {
  public enum Intent { DISCUSS, PREPARE, DECIDE }
  private UmlProposalIntent() { }
  public static Intent classify(String content) {
    String text=Normalizer.normalize(content.trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
    text=text.replaceFirst("^(ia|agente|asistente)[,: ]+", "");
    if(text.matches("^(si[, ]*|por favor[, ]*)?(aplica|aplicar|acepta|aceptar|rechaza|rechazar|confirma|confirmar)\\b.*")
        || text.matches("^(si|ok|dale|adelante|hazlo)[.! ]*$")) return Intent.DECIDE;
    if(text.matches("^(por favor[, ]*)?(prepara|preparar|propone|proponer|genera|generar|crea|crear|agrega|agregar|anade|anadir|modifica|modificar|actualiza|actualizar|cambia|cambiar|elimina|eliminar|borra|borrar|relaciona|relacionar)\\b.*")) return Intent.PREPARE;
    return Intent.DISCUSS;
  }
}
