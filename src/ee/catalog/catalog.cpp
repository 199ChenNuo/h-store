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

#include <cstdlib>
#include <cassert>
#include <iostream>
#include <stdio.h>
#include <string.h>
#include "catalog.h"
#include "cluster.h"
#include "common/debuglog.h"
#include "common/SerializableEEException.h"

using namespace voltdb;
using namespace catalog;
using namespace std;

Catalog::Catalog()
: CatalogType(this, NULL, "/", "catalog"), m_clusters(this, this, "/clusters") {
    m_allCatalogObjects["/"] = this;
    m_childCollections["clusters"] = &m_clusters;
    m_relativeIndex = 1;
}

Catalog::~Catalog() {
    boost::unordered_map<std::string, CatalogType*>::iterator iter;
    for (iter = m_allCatalogObjects.begin(); iter != m_allCatalogObjects.end(); iter++) {
        CatalogType *ct = iter->second;
        if (ct != this)
            delete ct;
    }
}

void Catalog::execute(const string &stmts) {
    vector<string> lines = splitString(stmts, '\n');
    for (int32_t i = 0; i < lines.size(); ++i) {
        executeOne(lines[i]);
    }
    if (m_unresolved.size() > 0) {
        VOLT_ERROR("Number of Unresolved References: %d", (int)m_unresolved.size());
        int32_t i = 0;
        for (std::map<std::string, std::list<UnresolvedInfo> >::const_iterator iter = m_unresolved.begin(); iter != m_unresolved.end(); iter++) {
            VOLT_ERROR("[%02d] %s", i, iter->first.c_str());
            i += 1;
        } // FOR
        throw SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION,
                                      "failed to execute catalog");
    }
}

void Catalog::executeOne(const string &stmt) {
    // FORMAT:
    // add ref collection name
    // set ref fieldname value
    // ref = path | guid
    // parsed as: (command ref a b)

    // parse
    size_t pos = 0;
    size_t end = stmt.find(' ', pos);
    string command = stmt.substr(pos, end - pos);
    pos = end + 1;
    end = stmt.find(' ', pos);
    string ref = stmt.substr(pos, end - pos);
    pos = end + 1;
    end = stmt.find(' ', pos);
    string a = stmt.substr(pos, end - pos);
    pos = end + 1;
    end = stmt.length() + 1;
    string b = stmt.substr(pos, end - pos);

    VOLT_DEBUG("Statement: %s\nCommand:%s | Ref:%s | A:%s | B:%s", \
               stmt.c_str(), command.c_str(), ref.c_str(), a.c_str(), b.c_str());

    CatalogType *item = itemForRef(ref);
    assert(item != NULL);

    // execute
    if (command.compare("add") == 0) {
        CatalogType *type = item->addChild(a, b);
        if (type == NULL) {
            VOLT_ERROR("Invalid Catalog Statement: %s", stmt.c_str());
            throw SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION,
                                          "failed to add child");
        }
        std::string path = type->path();
        if (m_unresolved.count(path) != 0) {
            //printf("catalog unresolved has a match for path: %s\n", path.c_str());
            //fflush(stdout);
            std::list<UnresolvedInfo> lui = m_unresolved[path];
            m_unresolved.erase(path);
            std::list<UnresolvedInfo>::const_iterator iter;
            for (iter = lui.begin(); iter != lui.end(); iter++) {
                UnresolvedInfo ui = *iter;
                std::string path2 = "set " + ui.type->path() + " "
                    + ui.field + " " + path;
                //printf("running unresolved command:\n    %s\n", path2.c_str());
                //fflush(stdout);
                executeOne(path2);
            }
        }
    }
    else if (command.compare("set") == 0) {
        item->set(a, b);
    }
    else if (command.compare("delete") == 0) {
        item->removeChild(a, b);
    }
    else {
        VOLT_ERROR("Invalid Catalog Statement: %s", stmt.c_str());
        throw SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION,
                                      "command isn't 'set' or 'add'.");
    }
}

const CatalogMap<Cluster> & Catalog::clusters() const {
    return m_clusters;
}

CatalogType *Catalog::itemForRef(const string &ref) {
    // if it's a path
    boost::unordered_map<std::string, CatalogType*>::const_iterator iter;
    iter = m_allCatalogObjects.find(ref);
    if (iter != m_allCatalogObjects.end())
        return iter->second;
    else
        return NULL;
}

CatalogType *Catalog::itemForPath(const CatalogType *parent, const string &path) {
    string realpath = path;
    if (path.at(0) == '/')
        realpath = realpath.substr(1);

    // root case
    if (realpath.length() == 0)
        return this;

    vector<string> parts = splitToTwoString(realpath, '/');

    // child of root
    if (parts.size() <= 1)
        return itemForPathPart(parent, parts[0]);

    CatalogType *nextParent = itemForPathPart(parent, parts[0]);
    if (nextParent == NULL)
        return NULL;
    return itemForPath(nextParent, parts[1]);
}

CatalogType *Catalog::itemForPathPart(const CatalogType *parent, const string &pathPart) const {
    vector<string> parts = splitToTwoString(pathPart, '[');
    if (parts.size() <= 1)
        return NULL;
    parts[1] = splitString(parts[1], ']')[0];
    return parent->getChild(parts[0], parts[1]);
}

void Catalog::registerGlobally(CatalogType *catObj) {
    m_allCatalogObjects[catObj->path()] = catObj;
}

void Catalog::update() {
    // nothing to do
}

vector<string> Catalog::splitString(const string &str, char delimiter) {
    vector<string> vec;
    size_t begin = 0;
    while (true) {
        size_t end = str.find(delimiter, begin);
        if (end == string::npos) {
            if (begin != str.size()) {
                vec.push_back(str.substr(begin));
            }
            break;
        }
        vec.push_back(str.substr(begin, end - begin));
        begin = end + 1;
    }
    return vec;
}
vector<string> Catalog::splitToTwoString(const string &str, char delimiter) {
    vector<string> vec;
    size_t end = str.find(delimiter);
    if (end == string::npos) {
        vec.push_back(str);
    } else {
        vec.push_back(str.substr(0, end));
        vec.push_back(str.substr(end + 1));
    }
    return vec;
}

void Catalog::addUnresolvedInfo(std::string path, CatalogType *type, std::string fieldName) {
    assert(type != NULL);

    UnresolvedInfo ui;
    ui.field = fieldName;
    ui.type = type;

    std::list<UnresolvedInfo> lui = m_unresolved[path];
    lui.push_back(ui);
    m_unresolved[path] = lui;
}

CatalogType *Catalog::addChild(const string &collectionName, const string &childName) {
    if (collectionName.compare("clusters") == 0) {
        CatalogType *exists = m_clusters.get(childName);
        if (exists)
            throw SerializableEEException(VOLT_EE_EXCEPTION_TYPE_EEEXCEPTION,
                                          "trying to add a duplicate value.");
        return m_clusters.add(childName);
    }

    return NULL;
}

CatalogType *Catalog::getChild(const string &collectionName, const string &childName) const {
    if (collectionName.compare("clusters") == 0)
        return m_clusters.get(childName);
    return NULL;
}

void Catalog::removeChild(const std::string &collectionName, const std::string &childName) {
    assert (m_childCollections.find(collectionName) != m_childCollections.end());
    if (collectionName.compare("clusters") == 0)
        return m_clusters.remove(childName);
}

/** takes in 0-F, returns 0-15 */
int32_t hexCharToInt(char c) {
    c = static_cast<char>(toupper(c));
    assert ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'));
    int32_t retval;
    if (c >= 'A')
        retval = c - 'A' + 10;
    else
        retval = c - '0';
    assert(retval >=0 && retval < 16);
    return retval;
}

/** pass in a buffer at least half as long as the string */
void Catalog::hexDecodeString(const string &hexString, char *buffer) {
    assert (buffer);
    int32_t i;
    for (i = 0; i < hexString.length() / 2; i++) {
        int32_t high = hexCharToInt(hexString[i * 2]);

        int32_t low = hexCharToInt(hexString[i * 2 + 1]);
        int32_t result = high * 16 + low;
        assert (result >= 0 && result < 256);
        buffer[i] = static_cast<char>(result);
    }
    buffer[i] = '\0';
}

/** pass in a buffer at least twice as long as the string */
void Catalog::hexEncodeString(const char *string, char *buffer) {
    assert (buffer);
    int32_t i = 0;
    for (; i < strlen(string); i++) {
        char ch[2];
        sprintf(ch, "%x", (string[i] >> 4) & 0xF);
        buffer[i * 2] = ch[0];
        sprintf(ch, "%x", string[i] & 0xF);
        buffer[(i * 2) + 1] = ch[0];
    }
    buffer[i*2] = '\0';
}
