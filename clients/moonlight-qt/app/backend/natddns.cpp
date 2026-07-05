#include "natddns.h"

#include <QEventLoop>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkProxyFactory>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTimer>
#include <QUrl>

#include <stdexcept>

static QMap<quint16, quint16> parsePortMap(const QJsonObject& object, const char* name)
{
    QMap<quint16, quint16> ports;

    for (auto it = object.constBegin(); it != object.constEnd(); ++it) {
        bool ok = false;
        int originalPort = it.key().toInt(&ok);
        if (!ok || originalPort <= 0 || originalPort > 65535) {
            throw std::runtime_error(QString("Invalid %1 source port: %2").arg(name, it.key()).toUtf8().constData());
        }

        int mappedPort = it.value().toInt(-1);
        if (mappedPort <= 0 || mappedPort > 65535) {
            throw std::runtime_error(QString("Invalid %1 mapped port for %2").arg(name).arg(originalPort).toUtf8().constData());
        }

        ports.insert((quint16)originalPort, (quint16)mappedPort);
    }

    return ports;
}

static QJsonObject serializePortMap(const QMap<quint16, quint16>& ports)
{
    QJsonObject object;

    for (auto it = ports.constBegin(); it != ports.constEnd(); ++it) {
        object.insert(QString::number(it.key()), (int)it.value());
    }

    return object;
}

static QByteArray fetchUrl(const QUrl& parsedUrl, int timeoutMs, const QNetworkProxy& proxy)
{
    QNetworkAccessManager nam;
    nam.setProxy(proxy);

    QNetworkRequest request(parsedUrl);
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("User-Agent", "Moonlight-NAT-DDNS/1.0");
    request.setRawHeader("Connection", "close");
#if QT_VERSION >= QT_VERSION_CHECK(6, 0, 0)
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
#endif

    QNetworkReply* reply = nam.get(request);

    QEventLoop loop;
    QTimer timeout;
    timeout.setSingleShot(true);
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    QObject::connect(&timeout, &QTimer::timeout, &loop, &QEventLoop::quit);
    timeout.start(timeoutMs);
    loop.exec(QEventLoop::ExcludeUserInputEvents);

    if (!reply->isFinished()) {
        reply->abort();
        reply->deleteLater();
        throw std::runtime_error("request timed out");
    }

    QByteArray body = reply->readAll();
    QVariant statusCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
    if (reply->error() != QNetworkReply::NoError) {
        QString error = QStringLiteral("%1 (Qt network error %2)")
                .arg(reply->errorString())
                .arg((int)reply->error());
        reply->deleteLater();
        throw std::runtime_error(error.toUtf8().constData());
    }
    if (statusCode.isValid() && (statusCode.toInt() < 200 || statusCode.toInt() >= 300)) {
        int status = statusCode.toInt();
        reply->deleteLater();
        throw std::runtime_error(QString("HTTP %1").arg(status).toUtf8().constData());
    }

    reply->deleteLater();
    return body;
}

bool NatDdnsMapping::isValid() const
{
    return !host.isEmpty() && !tcp.isEmpty();
}

quint16 NatDdnsMapping::mapTcpPort(quint16 port) const
{
    return tcp.value(port, port);
}

quint16 NatDdnsMapping::mapUdpPort(quint16 port) const
{
    return udp.value(port, port);
}

QString NatDdnsMapping::display() const
{
    if (!isValid()) {
        return QString();
    }

    QStringList lines;
    lines.append(QStringLiteral("NAT-DDNS URL: %1").arg(sourceUrl));
    lines.append(QStringLiteral("NAT-DDNS Host: %1").arg(host));
    if (fetchedAt.isValid()) {
        lines.append(QStringLiteral("NAT-DDNS Updated: %1").arg(fetchedAt.toLocalTime().toString(Qt::ISODate)));
    }

    for (auto it = tcp.constBegin(); it != tcp.constEnd(); ++it) {
        lines.append(QStringLiteral("TCP %1 -> %2").arg(it.key()).arg(it.value()));
    }
    for (auto it = udp.constBegin(); it != udp.constEnd(); ++it) {
        lines.append(QStringLiteral("UDP %1 -> %2").arg(it.key()).arg(it.value()));
    }

    return lines.join('\n');
}

QByteArray NatDdnsMapping::toJson() const
{
    QJsonObject object;
    object.insert(QStringLiteral("host"), host);
    object.insert(QStringLiteral("tcp"), serializePortMap(tcp));
    object.insert(QStringLiteral("udp"), serializePortMap(udp));
    if (!sourceUrl.isEmpty()) {
        object.insert(QStringLiteral("sourceUrl"), sourceUrl);
    }
    if (fetchedAt.isValid()) {
        object.insert(QStringLiteral("fetchedAt"), fetchedAt.toUTC().toString(Qt::ISODate));
    }

    return QJsonDocument(object).toJson(QJsonDocument::Compact);
}

NatDdnsMapping NatDdnsMapping::fromJson(const QByteArray& data, const QString& sourceUrl)
{
    QJsonParseError parseError;
    QJsonDocument document = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        throw std::runtime_error(QString("Invalid NAT-DDNS JSON: %1").arg(parseError.errorString()).toUtf8().constData());
    }

    QJsonObject object = document.object();
    NatDdnsMapping mapping;
    mapping.sourceUrl = !sourceUrl.isEmpty() ? sourceUrl : object.value(QStringLiteral("sourceUrl")).toString();
    mapping.host = object.value(QStringLiteral("host")).toString();
    if (mapping.host.isEmpty()) {
        throw std::runtime_error("NAT-DDNS JSON is missing host");
    }

    QJsonObject tcp = object.value(QStringLiteral("tcp")).toObject();
    if (tcp.isEmpty()) {
        throw std::runtime_error("NAT-DDNS JSON is missing tcp port mappings");
    }

    mapping.tcp = parsePortMap(tcp, "TCP");
    mapping.udp = parsePortMap(object.value(QStringLiteral("udp")).toObject(), "UDP");
    mapping.fetchedAt = QDateTime::currentDateTimeUtc();

    QString fetchedAt = object.value(QStringLiteral("fetchedAt")).toString();
    if (!fetchedAt.isEmpty()) {
        QDateTime parsed = QDateTime::fromString(fetchedAt, Qt::ISODate);
        if (parsed.isValid()) {
            mapping.fetchedAt = parsed.toUTC();
        }
    }

    return mapping;
}

NatDdnsMapping NatDdnsResolver::fetch(const QString& url, int timeoutMs)
{
    QUrl parsedUrl(url);
    if (!parsedUrl.isValid() || parsedUrl.host().isEmpty() ||
            (parsedUrl.scheme() != QStringLiteral("http") && parsedUrl.scheme() != QStringLiteral("https"))) {
        throw std::runtime_error("NAT-DDNS URL must be an HTTP or HTTPS URL");
    }

    QList<QNetworkProxy> proxies = QNetworkProxyFactory::systemProxyForQuery(QNetworkProxyQuery(parsedUrl));
    proxies.append(QNetworkProxy::NoProxy);

    QStringList errors;
    for (const QNetworkProxy& proxy : std::as_const(proxies)) {
        try {
            QByteArray body = fetchUrl(parsedUrl, timeoutMs, proxy);
            return NatDdnsMapping::fromJson(body, url);
        } catch (const std::exception& e) {
            QString proxyName = proxy.type() == QNetworkProxy::NoProxy
                    ? QStringLiteral("direct")
                    : QStringLiteral("%1:%2").arg(proxy.hostName()).arg(proxy.port());
            errors.append(QStringLiteral("%1: %2").arg(proxyName, QString::fromUtf8(e.what())));
        }
    }

    throw std::runtime_error(QString("NAT-DDNS request failed for %1. Attempts: %2")
                             .arg(url, errors.join(QStringLiteral("; ")))
                             .toUtf8()
                             .constData());
}
