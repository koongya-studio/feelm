/*
 * 8.1 알림 종류 아이콘. 와이어프레임은 원 안에 비워 두었다 — 모양은 기존 아이콘에서 고른다.
 */
import { FilmIcon, PartyGroupIcon, PlayIcon } from '../../../components/Icon';
import type { NotificationItem } from '../../../types/notification';

export default function NotificationIcon({ type }: { type: NotificationItem['type'] }) {
  switch (type) {
    case 'WATCH_CONFIRM':
      return <PlayIcon />;
    case 'PARTY_INVITE':
    case 'PARTY_INVITE_ACCEPTED':
      return <PartyGroupIcon />;
    case 'REPORT_ISSUED':
      return <FilmIcon />;
  }
}
