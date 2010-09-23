/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

/* WARNING: THIS FILE IS AUTO-GENERATED
            DO NOT MODIFY THIS SOURCE
            ALL CHANGES MUST BE MADE IN THE CATALOG GENERATOR */

#include <cassert>
#include "connectordestinationinfo.h"
#include "catalog.h"

using namespace catalog;
using namespace std;

ConnectorDestinationInfo::ConnectorDestinationInfo(Catalog *catalog, CatalogType *parent, const string &path, const string &name)
: CatalogType(catalog, parent, path, name)
{
    CatalogValue value;
    m_fields["url"] = value;
    m_fields["username"] = value;
    m_fields["password"] = value;
}

void ConnectorDestinationInfo::update() {
    m_url = m_fields["url"].strValue.c_str();
    m_username = m_fields["username"].strValue.c_str();
    m_password = m_fields["password"].strValue.c_str();
}

CatalogType * ConnectorDestinationInfo::addChild(const std::string &collectionName, const std::string &childName) {
    return NULL;
}

CatalogType * ConnectorDestinationInfo::getChild(const std::string &collectionName, const std::string &childName) const {
    return NULL;
}

void ConnectorDestinationInfo::removeChild(const std::string &collectionName, const std::string &childName) {
    assert (m_childCollections.find(collectionName) != m_childCollections.end());
}

const string & ConnectorDestinationInfo::url() const {
    return m_url;
}

const string & ConnectorDestinationInfo::username() const {
    return m_username;
}

const string & ConnectorDestinationInfo::password() const {
    return m_password;
}

