# Notices

This repository contains modified versions of open source Moonlight clients.

## Upstream projects

- `clients/moonlight-android` is based on Moonlight Android:
  <https://github.com/moonlight-stream/moonlight-android>
- `clients/moonlight-qt` is based on Moonlight PC / Moonlight Qt:
  <https://github.com/moonlight-stream/moonlight-qt>
- Both upstream projects are part of the Moonlight Game Streaming Project:
  <https://moonlight-stream.org>

## License

The Moonlight client source trees are licensed under the GNU General Public License version 3. Their original license files and upstream README files are kept in their respective directories:

- `clients/moonlight-android/LICENSE.txt`
- `clients/moonlight-qt/LICENSE`

This repository publishes the modified client source code so users can inspect, modify, and rebuild the clients under the same license terms.

The `clients/android-moonlight-nat-adapter` directory is an Android proof of concept for NAT-DDNS/VPN-based testing. It is included as supporting source for the same overall NAT-DDNS streaming experiment.

## Non-affiliation

This repository is not an official Moonlight project and is not maintained by the Moonlight or Sunshine upstream maintainers. Moonlight, Sunshine, and related names belong to their respective projects and maintainers.

## Website Source

The companion website at `stunddns.top` is used as the NAT-DDNS control plane for this client bundle. The website source code is not included in this repository.
