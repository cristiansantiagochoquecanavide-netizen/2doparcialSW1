package bo.edu.caseapp.chat;

import java.util.regex.Pattern;

public final class AcodiMention {
  private static final Pattern MENTION=Pattern.compile("(?<![\\p{L}\\p{N}_@.+-])@acodi(?![\\p{L}\\p{N}_@.+-])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
  private AcodiMention() { }
  public static boolean present(String text) { return text!=null && MENTION.matcher(text).find(); }
  public static String question(String text) { return MENTION.matcher(text==null?"":text).replaceAll("").trim(); }
}
