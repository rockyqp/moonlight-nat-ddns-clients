#pragma once

#include <QDateTime>
#include <QMap>
#include <QString>

class NatDdnsMapping
{
public:
    QString sourceUrl;
    QString host;
    QMap<quint16, quint16> tcp;
    QMap<quint16, quint16> udp;
    QDateTime fetchedAt;

    bool isValid() const;
    quint16 mapTcpPort(quint16 port) const;
    quint16 mapUdpPort(quint16 port) const;
    QString display() const;

    QByteArray toJson() const;

    static NatDdnsMapping fromJson(const QByteArray& data, const QString& sourceUrl = QString());
};

class NatDdnsResolver
{
public:
    static NatDdnsMapping fetch(const QString& url, int timeoutMs = 5000);
};
