%{--
  - Copyright (c) 2010 iceScrum Technologies.
  -
  - This file is part of iceScrum.
  -
  - iceScrum is free software: you can redistribute it and/or modify
  - it under the terms of the GNU Lesser General Public License as published by
  - the Free Software Foundation, either version 3 of the License.
  -
  - iceScrum is distributed in the hope that it will be useful,
  - but WITHOUT ANY WARRANTY; without even the implied warranty of
  - MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  - GNU General Public License for more details.
  -
  - You should have received a copy of the GNU Lesser General Public License
  - along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
  --}%
<div class="widget-bar">
  <div id="widget-list">
    <sec:ifLoggedIn>
        <div class="upgrade" style="display:none;">
            <span class="close"><g:message code="is.ui.hide"/></span>
            <g:message code="is.ui.hide"/>
        </div>
    </sec:ifLoggedIn>
  </div>
</div>


