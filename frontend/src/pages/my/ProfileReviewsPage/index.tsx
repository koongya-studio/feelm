import { useParams } from 'react-router';
import ProfileReviews from '../../../components/ProfileReviews';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';
import styles from '../../../components/ProfileReviews/ProfileReviews.module.css';
export default function ProfileReviewsPage() {
  const { publicId = '' } = useParams();
  return (
    <main className={styles.page}>
      <ScreenHeader title="공개 한줄평" showBack fallbackTo={path.publicProfile(publicId)} />
      <ProfileReviews publicId={publicId} />
    </main>
  );
}
