/*
 * Copyright (c) 2010 iceScrum Technologies.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Damien Vitrac (damien@oocube.com)
 */

package org.icescrum.core.taglib

import org.icescrum.components.UtilsWebComponents

class TableTagLib {
    static namespace = 'is'

    def tableView = {attrs, body ->
        out << "<div class=\"view-table\">"
        out << body()
        out << "</div>"
    }

    def table = {attrs, body ->
        pageScope.rowHeaders = []
        pageScope.tableRows = []
        pageScope.tableGroup = []
        body()

        def jqCode = ""
        def maxCols = pageScope.rowHeaders.size()

        if (!attrs.onlyRows) {
            out << '<table style="' + (attrs.style ?: '') + '"  cellspacing="0" cellmargin="0" border="0" ' + (attrs.id ? "id=\"${attrs.id}\" " : '') + 'class="table ' + (attrs."class" ?: '') + '">'
            // Header
            out << "<thead>"
            out << '<tr class="table-legend">'
            pageScope.rowHeaders.eachWithIndex { col, index ->
                col."class" = col."class" ?: ""
                out << '<th ' + (col.width ? ' style=\'width:' + col.width + '\'' : '') + ' class="break-word ' + col."class" + '"><div class=\"table-cell\">' + is.nbps(null, col.name) + (attrs.sortableCols ?'<div class="sorter"></sorter>' :'') +'</div></th>'
            }
            out << '</tr>'
            out << "</thead>"
            out << "<tbody>"
        }


        def maxRows = 0
        pageScope.tableGroup?.rows*.size().each {maxRows += it}
        maxRows = maxRows + (pageScope.tableRows?.size() ?: 0)

        pageScope.tableGroup.eachWithIndex { group, indexGroup ->
            out << '<tr class="table-line table-group" data-elemid="' + group.elementId + '">'
            out << '<td ' + (group.header.class ? 'class="' + group.header.class + '"' : '') + ' class="collapse" colspan="' + maxCols + '">' + (group.header.body ? group.header.body() : '') + '</td>'
            out << '</tr>'
            def editables = [:]
            maxRows = writeRows(group.rows, maxRows, maxCols, editables, 'table-group-' + group.elementId)
            if (group.editable) {
                group.editable.var = group.rows[0]?.attrs?.var ?: null
                if (group.editable.var)
                    jqCode += writeEditable(group.editable, editables)
            }
        }
        if (maxRows > 0) {
            def editables = [:]
            writeRows(pageScope.tableRows, maxRows, maxCols, editables)
            if (attrs.editable) {
                attrs.editable.var = pageScope.tableRows?.attrs?.var[0] ?: null
                if (attrs.editable.var)
                    jqCode += writeEditable(attrs.editable, editables)
            }

        }

        if (!attrs.onlyRows) {
            // end
            out << "<tbody>"
            out << '</table>'
            jqCode += "jQuery('#${attrs.id}').table({sortable:${attrs.sortableCols?:false}});"
            out << jq.jquery(null, jqCode)
        }
    }

    /**
     * Helper tag for a Table header
     */
    def tableHeader = { attrs, body ->
        if (pageScope.rowHeaders == null) return
        def options = [
                name: attrs.name,
                key: attrs.key,
                width: attrs.width ?: null,
                'class': attrs."class",
                body: body ?: null
        ]
        pageScope.rowHeaders << options
    }

    /**
     * Helper tag for the Table rows
     */
    def tableRows = { attrs, body ->
        attrs.'in'.eachWithIndex { row, indexRow ->
            def attrsCloned = attrs.clone()
            attrsCloned[attrs.var] = row
            pageScope.tableColumns = []
            body(attrsCloned)
            def columns = pageScope.tableColumns.clone()
            attrsCloned.remove('in')
            def options = [
                    columns: columns,
                    attrs: attrsCloned,
                    elemid: attrs.elemid ? row."${attrs.elemid}" : null,
                    "data-rank": attrs."data-rank" ? row."${attrs."data-rank"}" : null
            ]

            pageScope.tableRows << options
        }
    }

    /**
     * Helper tag for a specific Table row
     */
    def tableRow = { attrs, body ->
        pageScope.tableColumns = []
        body()
        def options = [
                columns: pageScope.tableColumns,
                attrs: attrs,
                elemid: attrs.elemid ? attrs.elemid : null,
                "data-rank": attrs."data-rank" ?: null
        ]
        pageScope.tableRows << options
    }

    /**
     * Helper tag for a specific Table row
     */
    def tableGroupHeader = { attrs, body ->
        def options = [
                class: attrs.class ?: null,
                body: body ?: null
        ]
        pageScope.groupHeader = options
    }

    /**
     * Helper tag for a group rows
     */
    def tableGroup = { attrs, body ->

        if (!UtilsWebComponents.rendered(attrs))
            return

        pageScope.tableRows = []
        pageScope.groupHeader = null
        body()
        def options = [
                rows: pageScope.tableRows,
                header: pageScope.groupHeader,
                editable: attrs.editable ?: null,
                elementId: attrs.elementId
        ]
        pageScope.tableGroup << options
        pageScope.tableRows = null
    }

    /**
     * Helper tag for the column content
     */
    def tableColumn = { attrs, body ->
        if (pageScope.tableColumns == null) return

        def options = [
                key: attrs.key,
                editable: attrs.editable ?: null,
                'class': attrs.'class',
                body: body ?: null
        ]
        pageScope.tableColumns << options
    }

    private writeRows(def rows, def maxRows, def maxCols, def editables, def groupid = null) {
        def nbRows = 0
        rows.eachWithIndex { row, indexRow ->
            nbRows++

            def version
            if (!row.attrs.version){
                row.attrs.version = 0
                if (row?.attrs?."${row.attrs.var}"?.version == 0) {
                    version = 0
                } else if (row?.attrs?."${row.attrs.var}"?.version == null) {
                    version = 0
                } else {
                    version = row?.attrs?."${row.attrs.var}"?.version
                }
            }else{
                version = row.attrs.version
            }

            if (row.attrs.rowClass in Closure) {
                row.attrs.rowClass.delegate = delegate
                row.attrs.rowClass = row.attrs.rowClass(row.attrs."${row.attrs.var}")
            }
            def htmlRank = row.'data-rank' ? '" data-rank="' + row.'data-rank' : ''
            out << '<tr class="table-line ' + (row.attrs.rowClass ? row.attrs.rowClass : '') + ' ' + (groupid ? groupid : '') + '" data-elemid="' + row.elemid + '" version="' + version + '"' + htmlRank + '">'
            row.columns.eachWithIndex { col, indexCol ->

                //gestion editable
                def editable = col.editable?.name ?: col.editable?.type ?: null
                if (editable && !editables."${editable}") {
                    editables."${editable}" = [id: col.editable.id ?: '', type: col.editable.type, values: col.editable.values ?: null, detach: col.editable.detach ?: false, highlight: col.editable.highlight ?: false]
                }
                col['class'] = col['class'] ?: ""
                out << '<td class="' + col['class'] + ' break-word">'
                out << '<div ' + editableCell(col.editable) + '>'
                out << is.nbps( null, col.body ? col.body(row.attrs) : '' ) + '</div></td>'
            }
            out << '</tr>'
        }
        return maxRows - nbRows
    }

    private writeEditable(def editableConfig, def editables) {
        def jqCode = ''
        editables?.each {k, v ->
            editableConfig.type = v.type
            editableConfig.id = v.id
            editableConfig.values = v.values
            editableConfig.detach = v.detach
            editableConfig.highlight = v.highlight
            jqCode = jqCode + is.jeditable(editableConfig)
            editableConfig.remove('type')
            editableConfig.remove('values')
            editableConfig.remove('id')
        }
        return jqCode
    }

    def nbps = {attrs, body ->
        out << body()?.trim() ?: ''
    }

    private editableCell(def attrs) {
        if (attrs && attrs.type && attrs.name && !attrs.disabled)
            return 'class="table-cell table-cell-editable table-cell-' + attrs.type + (attrs.id ? '-' + attrs.id : '') + ' table-cell-editable-' + attrs.type + (attrs.id ? '-' + attrs.id : '') + '" name="' + attrs.name + '"'
        else {
            return 'class="table-cell"'
        }
    }

    def jeditable = {attrs ->
        def finder = ""
        def data = ""
        def original = "original.revert"
        def detach = "'${attrs.var}.'+\$(this).attr('name')"

        if (attrs.detach) {
            detach = "\$(this).attr('name')"
        }


        if (attrs.type == 'text') {
            finder = "\$(original).find('input').val()"
            data = "return jQuery.icescrum.htmlDecode(value);"
        }
        else if (attrs.type == 'textarea') {
            finder = "\$(original).find('textarea').val()"
            data = "var retval = value.replace(/<br[\\s\\/]?>/gi, '\\n'); return jQuery.icescrum.htmlDecode(retval);"
        }
        else if (attrs.type == 'datepicker') {
            finder = "\$(original).find('textarea').val()"
            data = "return jQuery.icescrum.htmlDecode(value);"
        }
        else if (attrs.type == 'richarea') {
            finder = "\$(original).find('textarea').val()"
            data = "return jQuery.icescrum.htmlDecode(value);"
        }
        else if (attrs.type == 'selectui') {
            finder = "\$('<div/>').html(\$(original).find('select').children('option:selected').text()).text();"
            data = "value = \$('<div/>').html(value).text(); return {${attrs.values},'selected':value};"
            original = "\$('<div/>').html(original.revert).text()"
        }
        def jqCode = """
                jQuery('.table-cell-editable-${attrs.type}${attrs.id ? '-' + attrs.id : ''}').die().liveEditable('${createLink(action: attrs.action, controller: attrs.controller, params: attrs.params)}',{
                    type:'${attrs.type}',
                    select:${attrs.highlight ?: false},
                    ajaxoptions:{dataType:'json'},
                    data : function(value, settings) {settings.name = ${detach}; settings.id = 'id';${data}},
                    onsubmit:function(settings, original){var finder = ${finder}; var origin = ${original}; if (finder == origin) { original.reset(); return false;}},
                    submitdata : function(value, settings) {return {'name':\$(this).attr('name'),'table':true,'id':\$(this).parent().parent().data('elemid'),'${attrs.var}.version':\$(this).parent().parent().attr('version')};},
                    callback:function(value, settings) {\$(this).html(value.value); \$(this).parent().parent().attr('version',value.version);${attrs.success}},
                    onblur:'${attrs.onExitCell}'
                    ${attrs.type == 'richarea' ? ", loaddata:function(revert, settings){settings.name = ${detach}; settings.id = 'id'; return {'id':\$(this).parent().parent().data('elemid')}},loadurl : '" + createLink(action: attrs.action, controller: attrs.controller, params: attrs.params) + "?loadrich=true',markitup : textileSettings" : ""}
                });
             """
        out << jqCode
    }
}