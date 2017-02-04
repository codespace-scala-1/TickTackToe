package ParseIP

object MyParser {

  def main(args: Array[String]) {

    val stringIP = io.StdIn.readLine("Enter IP, please, to parse it to Array[Byte]\n")
    val parser = new ParserIp(stringIP)
    parser.parseIp(stringIP) match {
      case Some(s) => println("result Array[Byte] = (" + s(0) + "," + s(1) +
        "," + s(2) + "," + s(3) + ")")
      case None => println("Wrong IP template!, must be [0-255].[0-255].[0-255].[0-255]")
    }

    val resultIP: Array[Byte] = parser.parseIp(stringIP).get

    println(parser.printIpFromByte(resultIP))
  }
}
