package shopee.recognition.entity

import java.io.StringReader

import scala.collection.mutable.HashSet
import scala.collection.JavaConversions._

import edu.stanford.nlp.ling.{CoreLabel, HasWord, TaggedWord}
import edu.stanford.nlp.process.{CoreLabelTokenFactory, DocumentPreprocessor, PTBTokenizer, TokenizerFactory}
import edu.stanford.nlp.tagger.maxent.MaxentTagger

/**
  * Process POS tagging, extract tokens out of text.
  * The purpose of applying POS tagging is to extract token which can be candidates of named entities.
  *
  * @author TuanTA
  * @since 2017-05-03 23:23
  */
object POS {

  var tagger:MaxentTagger = null

  /**
    * Init Tagger module [[MaxentTagger]]
    *
    * @param posPath
    */
  def init(posPath:String) = {
    println("SentenceSplitterTransform: inside\nINIT: LOADING POS MODEL")
    tagger = new MaxentTagger(posPath)
  }

  /**
    * Extract named entities out of given text
    *
    * @param text content to be extracted tokens
    * @return sequence of named entities candidates
    */
  def extract(text:String) = {

    var neChunks = HashSet[String]()
    val documentPreprocessor = new DocumentPreprocessor(new StringReader(text))
    documentPreprocessor.setTokenizerFactory(getTokenizerFactory())

    val it = documentPreprocessor.iterator()
    while (it.hasNext()) {
      val sentence = it.next()
      var chunksPerSentence = tokensFromSentence(sentence)
      neChunks ++= chunksPerSentence
    }

    neChunks.toSeq
  }

  /** Parse a sentence */
  private def tokensFromSentence(sentence:java.util.List[HasWord]):HashSet[String] = {
    val nnp = HashSet[String]()
    val tSentence:List[TaggedWord] = tagger.tagSentence(sentence).toList

    var last = "aa"
    var last_w = "aa"
    var nnp_s0 = ""
    val sentenceLength = tSentence.length
    var ctr = 0
    for (tw <- tSentence) {
      ctr = ctr + 1
      val isCurrentNP = Utils.isProperNoun(tw.tag, tw.word)
      val isLastNP = Utils.isProperNoun(last, last_w)
      if (isCurrentNP || Utils.isSpecialAdjoiningTag(tw.tag(), tw.value())) {
        if (isLastNP)
          nnp_s0 = nnp_s0 + " " + tw.value()
        else if (isCurrentNP)
          nnp_s0 = tw.value()
      } else if (isLastNP) {
        if (!nnp_s0.trim().isEmpty())
          nnp.add(nnp_s0.trim())
        nnp_s0 = ""
      }
      if (ctr == sentenceLength - 1) {
        if (!nnp_s0.trim().isEmpty())
          nnp.add(nnp_s0.trim())
        nnp_s0 = ""
      }
      last = tw.tag()
      last_w = tw.word()
    }

    nnp
  }

  /**  */
  private def getTokenizerFactory():TokenizerFactory[CoreLabel] = {
    PTBTokenizer.factory(
      new CoreLabelTokenFactory(),
      "untokenizable=noneKeep,ptb3Escaping=false,ptb3Ellipsis=false,ptb3Dashes=false"
    )
  }
}
