#!/usr/bin/env groovy

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.6')

import groovyx.net.http.RESTClient
import javax.servlet.http.HttpServletResponse
import javax.xml.parsers.DocumentBuilderFactory
import groovy.xml.XmlUtil
import javax.xml.xpath.*
import static groovyx.net.http.ContentType.*

def usage(cli, error) {
  if (error) System.err << "${error}\n"
  cli.usage()
  System.exit(1)
}

def fail(error) {
  System.err << "${error}\n"
  System.exit(1)
}

def cli = new CliBuilder(usage: 'postXML.groovy -u url file [xpath=val ...] [file ...]')
cli._(longOpt:'help', 'Show this usage information')
cli.u(longOpt:'url', args:1, required:true, 'Destination URL')

def options = cli.parse(args)

if (!options) System.exit(1)

if (!options || options.help) usage(cli)

def conversions = [:]
def paths = []
for (arg in options.arguments()) {
  def matcher = (arg =~ /(.+)=(.*)/)
  if (matcher.matches()) {
    if (paths) usage(cli, "xpath conversion spec, ${arg}, must be before filenames")

    conversions.put(matcher[0][1], matcher[0][2])
  } else {
    paths += arg
  }
}

if (!paths) usage(cli, 'Must specify a least one filename')

def files = []
for(path in paths) {
  def file = new File(path)
  if (!file.exists()) fail("File ${file} does not exist\n")
  files += file
}

def url = options.url
def client = new RESTClient(url)

for (path in paths) {
  println "POSTing ${path}"

  def xmlDoc = new File(path).bytes

  def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
  def inputStream = new ByteArrayInputStream(xmlDoc)
  def document = builder.parse(inputStream).documentElement

  def xpath = XPathFactory.newInstance().newXPath()

  for (spec in conversions) {
    def nodes = xpath.evaluate( spec.key, document, XPathConstants.NODESET )
    nodes.eachWithIndex { node, i ->
      println "Setting ${spec.key} from ${node.value} to ${spec.value} [Match ${i+1} of ${nodes.length}]"
      node.value = spec.value
    }
  }

  def docText = XmlUtil.serialize(document)

  def response = client.post(
      requestContentType: TEXT,
      body: docText
  )
  println response.data

  if (response.status != HttpServletResponse.SC_OK) {
    fail("ERROR: Got response ${response.status}")
  }
}
