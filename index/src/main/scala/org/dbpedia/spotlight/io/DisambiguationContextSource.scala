package org.dbpedia.spotlight.io

/**
 * Copyright 2011 Pablo Mendes, Max Jakob
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.dbpedia.spotlight.string.WikiMarkupStripper
import org.dbpedia.spotlight.model._
import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.sources.Source
import org.apache.commons.logging.LogFactory

/**
 * Loads descriptive text that occurs around entries in disambiguation pages from a wiki dump.
 * @author maxjakob
 */
object DisambiguationContextSource extends WikipediaOccurrenceSourceFactory {

    protected def occurrenceSource(extractionSource: Source) = new DisambiguationContextSource(extractionSource)

    def getOccurrence(listItem : List[Node], surfaceForm : SurfaceForm, id : String) : Option[DBpediaResourceOccurrence] = {
        var uri = ""
        var disambiguationText = ""

        for (node <- listItem) {
            node match {
                // for text nodes, collect the paragraph text
                case textNode : TextNode => disambiguationText += textNode.text

                // for wiki page link nodes collect URI, surface form and offset
                // if the link points to a page in the Main namespace
                case internalLink : InternalLinkNode => {
                    if (uri.isEmpty && isDisambiguationUri(internalLink.destination, surfaceForm)) {
                        uri = internalLink.destination.encoded
                    }
                    disambiguationText += internalLink.children.collect{ case TextNode(text, _) => WikiMarkupStripper.stripMultiPipe(text) }.mkString("")
                }
                case _ =>
            }
        }

        if (disambiguationText.startsWith("*") && uri.nonEmpty) {
            disambiguationText = disambiguationText.replaceFirst("""^[\*\s]+""", "")
            val cutoff = math.max(disambiguationText.indexOf("""\n"""), disambiguationText.length)
            val textInstance = new Text(disambiguationText.slice(0, cutoff).replaceAll("""\s""", " "))
            val offset = textInstance.text.toLowerCase.indexOf(surfaceForm.name.toLowerCase)
            Some(new DBpediaResourceOccurrence(id, new DBpediaResource(uri), surfaceForm, textInstance, offset))
        }
        else {
            None
        }
    }

    private def isDisambiguationUri(destination : WikiTitle, surfaceForm : SurfaceForm) : Boolean = {
        destination.namespace == Namespace.Main &&
            // same heuristic as in DBpeida extraction
            (destination.decoded.contains(surfaceForm.name) || isAcronym(surfaceForm.name, destination.decoded))
    }

    // copied from DBpedia extraction framework
    private def isAcronym(acronym : String, destination : String) : Boolean = {
        if (acronym != acronym.toUpperCase) return false

        val destinationWithoutDash = destination.replace("-", " ")
        val destinationList = if (destinationWithoutDash.contains(" ")) destinationWithoutDash.split(" ")
        else destinationWithoutDash.split("")

        acronym.length == destinationList.foldLeft(0) ( (matchCount, word) =>
            if (matchCount < acronym.length && word.toUpperCase.startsWith(acronym(matchCount).toString))
                matchCount + 1
            else
                matchCount)
    }
}

/**
 * DBpediaResourceOccurrence Source which reads from a wiki pages source.
 */
class DisambiguationContextSource(wikiPages : Source, multiply : Int=1) extends OccurrenceSource {

    private val LOG = LogFactory.getLog(this.getClass)

    val splitDisambiguations = """\n"""

    val wikiParser = WikiParser()

    override def foreach[U](f : DBpediaResourceOccurrence => U) {
        var pageCount = 0
        var occCount = 0

        for (wikiPage <- wikiPages) {
            // clean the wiki markup from everything but links
            val cleanSource = WikiMarkupStripper.stripEverythingButBulletPoints(wikiPage.source)

            // parse the (clean) wiki page
            val pageNode = wikiParser( WikiPageUtil.copyWikiPage(wikiPage, cleanSource) )

            if (pageNode.isDisambiguation) {
                val surfaceForm = new SurfaceForm(wikiPage.title.decoded.replace(" (disambiguation)", "")) //TODO language-specific

                // split the page node into list items
                val listItems = NodeUtil.splitNodes(pageNode.children, splitDisambiguations)
                var itemsCount = 0
                for (listItem <- listItems) {
                    itemsCount += 1
                    val id = pageNode.title.encoded+"-pl"+itemsCount
                    DisambiguationContextSource.getOccurrence(listItem, surfaceForm, id) match {
                        case Some(occ) => (1 to multiply).foreach(i => f( occ )) ; occCount += 1
                        case None =>
                    }
                }

                pageCount += 1
                if (pageCount %5000 == 0) {
                    LOG.debug("Processed %d Wikipedia definition pages (avarage %.2f disambiguation sentences per page".format(pageCount, occCount/pageCount.toDouble))
                }
                if (pageCount %100000 == 0) {
                    LOG.info("Processed %d Wikipedia definition pages (avarage %.2f disambiguation sentences per page".format(pageCount, occCount/pageCount.toDouble))
                }
            }
        }
    }

}
