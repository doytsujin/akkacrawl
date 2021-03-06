import akka.util.ByteString
import scala.collection.mutable

/**
  * Created by dev on 12/3/16.
  */
trait WikiHtmlParsable {
  val isOpeningTag = "<[a-zA-Z]+(>|.*?[^?]>)".r
  val isClosingTag = "(<\\/.*?>)".r
  val extractNextTag = "(<.*?>)".r
  val extractText = ".+?(?=\\[--PIPE--\\])".r
  val eleType = "(\\w+)".r
  val extractId = "(id.*?\\\"(.*?)\\\")|(id.*?\\'(.*?)\\')".r
  val extractClass = "(class.*?\\\"(.*?)\\\")|(class.+\\'(.*?)\\')".r
  val extractFromQuotes = "(?<=(\"|')).*?(?=(\"|'))".r

  var configureStack = mutable.Stack[ifStreamCondition]()

  var configureArray: Array[mutable.Stack[ifStreamCondition]] = _

  def configure(configures: Array[mutable.Stack[ifStreamCondition]]): Unit = {
    this.configureArray = configures
  }

  case class ifStreamCondition(id: Option[String], ele: Option[String], clazz: Option[String], key: Option[String])
  case class IfTextMap(text: String, id: String)
  case class DomElement(ifTextMap: IfTextMap)

  var domStack = mutable.Stack[Option[DomElement]]()

  def compareIf(compare: ifStreamCondition): Array[ifStreamCondition] = {
    def condition(ifStack: mutable.Stack[ifStreamCondition]): Boolean = {
      if (ifStack.isEmpty) return false
      val ifO = ifStack.top
      ifO.id match {
        case Some(id) =>
          if (!Some(id).equals(compare.id))
            return false
        case None =>
      }
      ifO.ele match {
        case Some(ele) =>
          if (!Some(ele).equals(compare.ele))
            return false
        case None =>
      }
      ifO.clazz match {
        case Some(clazz) =>
          val clazzList = compare.clazz.getOrElse("").replaceAll("\\s+", " ").trim.split(" ")
          var flag = false
          clazzList.foreach((clazz) => flag = flag || (clazz.toLowerCase == clazz.toLowerCase))
          return flag
        case None =>
      }
      return true
    }

    val whichStack:Array[ifStreamCondition] = this.
      configureArray.
      withFilter(condition).map(_.pop())

    return whichStack
  }

  def getNewDataStringAndText(dataString: String):(String, String) = {
    var _dataString = "\\s+".r.replaceAllIn(dataString.replaceAll("\n"," "), " ")
    val text = extractText.findFirstIn(
      extractNextTag.replaceFirstIn(_dataString, "[--PIPE--]")
    ).getOrElse("")

    _dataString = _dataString.substring(text.size, _dataString.size)
    return (text, _dataString)
  }

  def mapToTextMap(domStack: mutable.Stack[Option[DomElement]], text: String): mutable.Stack[Option[DomElement]] = {
    return domStack.map((dom) =>
      dom match {
        case Some(dom) =>
          Some(DomElement(IfTextMap(dom.ifTextMap.text + text, dom.ifTextMap.id)))
        case _ => None
      }
    )
  }

  def iterateXml(tag: String, tagname: String, dataString: String, dataMap: Map[String, String]): (String, Map[String, String]) = {
    var _dataMap = dataMap
    // Lets extract our text
    val (text, _dataString) = getNewDataStringAndText(dataString)
    // Lets map our new text to our ifStack
    domStack = mapToTextMap(domStack, text)

    if (Constants.isSelfClosing(tagname)) {
      return (extractNextTag.replaceFirstIn(_dataString, ""), _dataMap)
    }

    // Before seeing if our new element fits our ifconditions
    // we must see what kind of tag it is

    // On close tag, we must pop our ifStack and build our map
    if (isClosingTag.findFirstIn(tag).equals(Some(tag))) {
      // Is this dom element associated with an if condition?
      domStack.pop() match {
        case Some(dom) =>
          _dataMap = _dataMap ++ Map(dom.ifTextMap.id -> dom.ifTextMap.text)
        case _ =>
      }

      return (extractNextTag.replaceFirstIn(_dataString, ""), _dataMap)
    }

    // If it's not a closing or self closing tag, then it must be an open tag

    // Now lets see if our new element fits our ifconditions

    val compare = ifStreamCondition(
      extractFromQuotes.
        findFirstIn(
          extractId.findFirstIn(tag).getOrElse("")
        ),
      Option(tagname),
      extractFromQuotes.
        findFirstIn(
          extractClass.findFirstIn(tag).getOrElse("")
        ),
      None
    )

    val ifMatch = compareIf(compare)

    if (ifMatch.length > 0) {
      // Our new element fits the bill, then lets add it to our
      // ifStack
      ifMatch(0).key match {
        case Some(key) =>
          domStack.push(Some(DomElement(IfTextMap("",key))))
        case None => domStack.push(None)
      }

    } else {
      domStack.push(None)
    }
    return (extractNextTag.replaceFirstIn(_dataString, ""), _dataMap)
  }

  def process(dataString:String, dataMap: Map[String, String]): (String, Map[String, String]) = {
    var _dataString = dataString
    var _dataMap = dataMap

    var loop = true
    // We want to minimize stack recursion so we use a loop
    while (loop) {
      extractNextTag findFirstIn _dataString match {
        case None => loop = false
        case Some(tag) =>
          eleType findFirstIn tag match {
            case None => loop = false
            case Some(tagname) =>
              val results = iterateXml(tag, tagname, _dataString, _dataMap)
              _dataString = results._1
              _dataMap = results._2
          }
      }
    }
    return (_dataString, _dataMap)
  }
  def htmlStreamReduce(data: (String, Map[String, String]), byteString: ByteString): (String, Map[String, String]) = {
    var dataString = data._1 + byteString.decodeString("UTF-8")
    // Ok now lets run process which should
    // process our data string until there's
    // nothing else it can do
    return process(dataString, data._2)
  }
}
