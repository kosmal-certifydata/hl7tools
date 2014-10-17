#!/usr/bin/env groovy

// Utility script that will read one or more files containing raw HL7 messages and send the message to the specified
// host and port.  The message contents are updated with a unique message control id and current timestamp.
// Command line options allow modification of message fields, e.g. ORC-2=2342 will set the contents of the ORC-2 field.
// (See http://hl7api.sourceforge.net/base/apidocs/index.html for a description of how to designate specific fields.)
//
// Files containing HL7 messages can be terminated with standard Unix newline '\n' and the script will replace with
// HL7 line endings '\r'
//

@Grab(group='ca.uhn.hapi', module='hapi-base', version='2.2')
@Grab(group='ca.uhn.hapi', module='hapi-structures-v26', version='2.2')
@Grab(group='org.slf4j', module='slf4j-simple', version='1.7.7')

import ca.uhn.hl7v2.DefaultHapiContext
import ca.uhn.hl7v2.HapiContext
import ca.uhn.hl7v2.app.Connection
import ca.uhn.hl7v2.app.Initiator
import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.model.v26.datatype.DTM
import ca.uhn.hl7v2.model.v26.datatype.ST
import ca.uhn.hl7v2.model.v26.segment.MSA
import ca.uhn.hl7v2.model.v26.segment.MSH
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory
import ca.uhn.hl7v2.parser.Parser
import ca.uhn.hl7v2.util.Terser
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory

import java.text.DateFormat
import java.text.SimpleDateFormat

System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

class ControlIdGenerator
{
  static def nextSequence = -1

  static def getUniqueControlId() {
    DateFormat controlIdFormatter = new SimpleDateFormat("yyyyMMddHHmmssSSS")
    nextSequence = (nextSequence + 1) % 100
    String.format("%s%03d", controlIdFormatter.format(new Date()), nextSequence)
  }
}

def usage(cli, error) {
  if (error) System.err << "${error}\n"
  cli.usage()
  System.exit(1)
}

def fail(error) {
  System.err << "${error}\n"
  System.exit(1)
}

def printableHl7(txt) {
  return txt.replace('\r', '\n');
}

def cli = new CliBuilder(usage: 'hl7send.groovy -p port [-h host] -[itmrc --no-root] [field=val ...] file [file ...]')
cli._(longOpt:'help', 'Show this usage information')
cli.h(longOpt:'hostname', args:1, 'Destination host (default localhost)')
cli.p(longOpt:'port', args:1, required:true, 'Destination port')
cli.i(longOpt:'no-id', 'Do not auto-generate Message Control Id (MSH-10)')
cli.t(longOpt:'no-timestamp', 'Do not update timestamp (MSH-7)')
cli.m(longOpt:'show-message', 'Show the text of the message that is sent')
cli.r(longOpt:'show-response', "Show the ACK message")
cli.c(longOpt:'continue-on-ack-error', 'Continue sending messages even if ACK response is failure or rejection')
cli._(longOpt:'no-root', 'Do not prepend root prefix to HL7 field value specifications')

def options = cli.parse(args)

if (!options) System.exit(1)

if (!options || options.help) usage(cli)

def conversions = [:]
def paths = []
for (arg in options.arguments()) {
  def matcher = (arg =~ /(.+)=(.*)/)
  if (matcher.matches()) {
    if (paths) usage(cli, "Conversion spec, ${arg}, must be before filenames")

    conversions.put(matcher[0][1], matcher[0][2])
  } else {
    paths += arg
  }
}

if (!paths) usage(cli, 'Must specify a least one filename')

if (!options.port.isInteger()) {
  usage(cli, "Port must be a number")
}
int port = options.port.toInteger()
def hostname = options.hostname ?: "localhost"

HapiContext context = new DefaultHapiContext()
context.setValidationContext(ValidationContextFactory.noValidation())
CanonicalModelClassFactory mcf = new CanonicalModelClassFactory("2.6")
context.setModelClassFactory(mcf)
Parser parser = context.genericParser

for (path in paths) {
  def file = new File(path)
  if (!file.exists()) fail("File ${file} does not exist\n")

  def messageText = file.getText('UTF-8').replace('\n', '\r')

  Message hl7message = parser.parse(messageText)

  MSH msh = (MSH) hl7message.get("MSH");

  // Set Message Control ID
  if (!options.t) {
    def controlId = ControlIdGenerator.uniqueControlId
    ST controlId_ST = msh.messageControlID
    controlId_ST.setValue(controlId)
  }

  if (!options.d) {
    DTM timeStamp = msh.dateTimeOfMessage
    timeStamp.setValue(new Date())
  }

  Terser terser = new Terser(hl7message)
  for (spec in conversions) {
    def field = (options.'no-root' ? '' : '/.') + spec.key
    terser.set(field, spec.value)
  }

  if (options.m) println "Sending:\n${printableHl7(parser.encode(hl7message))}"

  Connection connection = context.newClient(hostname, port, false)

  Initiator initiator = connection.initiator
  Message response = initiator.sendAndReceive(hl7message)

  println "Sent ${msh.messageType.messageCode} ${msh.messageControlID}"

  if (options.r) println "Got response:\n${printableHl7(parser.encode(response))}"

  // Check the response
  MSA responseMSA = (MSA)response.get("MSA");
  if (!responseMSA) fail("Response is not a valid ack")

  def ackMsg;
  switch (responseMSA.acknowledgmentCode.value) {
    case "AA":
      break
    case "AR":
      ackMsg = "REJECTED"
      break;
    default:
      ackMsg = "ERROR"
      break
  }
  if (ackMsg) {
    System.err << "${msh.messageType.messageCode} ${msh.messageControlID} got ${ackMsg} in ACK resposne\n"
    if (!options.c) fail("Message not accepted by receiver")
  }
}

System.exit(0)