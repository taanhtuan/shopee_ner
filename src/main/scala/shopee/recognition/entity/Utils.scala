package shopee.recognition.entity

/**
  * Utilities of string functions
  *
  * @author TuanTA
  * @since 2017-05-03 22:41
  */
object Utils {

  def textCleaning(s:String):String = {
    s.replaceAll("[\\`\\~\\!\\@\\#\\$\\%\\^\\&\\*\\(\\)\\-\\+\\=\\-\\{\\}\\[\\]\\;\\:\\'\\\"\\<\\>\\,\\.\\/\\?]", " ")
     .replaceAll("\\w*\\d\\w* *", " ")
  }

  def isProperNoun(tag:String, word:String):Boolean = {
    tag.startsWith("NNP")&&(!word.startsWith("http"))
  }

  def isSpecialAdjoiningTag(tag:String, word:String):Boolean = {
    var a = word.replaceAll("[\\,\\@\\&\\-\\(\\)\\\"\\']+", "")
    var ret = false
    if (a.trim().isEmpty())
      ret = true
    if (tag.equals(word))
      ret = true
    ret
  }

}
