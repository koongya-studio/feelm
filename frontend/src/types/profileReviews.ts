/** API-087 사용자별 공개 한줄평 */
export type ProfileReview = {
  ratingId: number;
  movie: { movieId: number; title: string; posterUrl: string | null };
  score: number;
  comment: string;
  spoiler: boolean;
  createdAt: string;
};
export type ProfileReviewPage = {
  items: ProfileReview[];
  nextCursor: string | null;
  hasNext: boolean;
};
